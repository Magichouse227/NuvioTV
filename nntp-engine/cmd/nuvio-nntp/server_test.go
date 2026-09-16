package main

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestSessionSetupResponseDoesNotExposeProviderCredentials(t *testing.T) {
	registry := newSessionRegistry(1, time.Minute)
	defer registry.closeAll()
	registry.newSession = func(context.Context, createSessionRequest) (*engineSession, error) {
		return nil, setupFailure("provider_connection", errors.New("private-user:private-password?apikey=private-key"))
	}
	api := newAPIServer(registry, "http://127.0.0.1:8191", "test-management-token-at-least-32-bytes")
	request := httptest.NewRequest(http.MethodPost, "/v1/sessions", strings.NewReader("{}"))
	request.Header.Set(managementTokenHeader, "test-management-token-at-least-32-bytes")
	response := httptest.NewRecorder()
	api.routes().ServeHTTP(response, request)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("status = %d", response.Code)
	}
	var payload errorResponse
	if err := json.Unmarshal(response.Body.Bytes(), &payload); err != nil {
		t.Fatal(err)
	}
	if payload.Code != "provider_connection" || strings.Contains(response.Body.String(), "private-") {
		t.Fatalf("unsafe or unclassified response: %s", response.Body.String())
	}
}

func TestHealthIsLoopbackAPIReady(t *testing.T) {
	registry := newSessionRegistry(1, time.Minute)
	defer registry.closeAll()
	api := newAPIServer(registry, "http://127.0.0.1:8191", "test-management-token-at-least-32-bytes")
	request := httptest.NewRequest(http.MethodGet, "/health", nil)
	request.Header.Set(managementTokenHeader, "test-management-token-at-least-32-bytes")
	response := httptest.NewRecorder()
	api.routes().ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("health status = %d", response.Code)
	}
}

func TestManagementAPIRejectsMissingToken(t *testing.T) {
	registry := newSessionRegistry(1, time.Minute)
	defer registry.closeAll()
	api := newAPIServer(registry, "http://127.0.0.1:8191", "test-management-token-at-least-32-bytes")
	request := httptest.NewRequest(http.MethodGet, "/health", nil)
	response := httptest.NewRecorder()
	api.routes().ServeHTTP(response, request)
	if response.Code != http.StatusUnauthorized {
		t.Fatalf("health status without token = %d", response.Code)
	}
}

var errMediaRead = errors.New("NNTP segment unavailable")

type shortMediaStream struct {
	data   []byte
	offset int64
	failAt int64
}

func (s *shortMediaStream) Read(p []byte) (int, error) {
	if s.offset >= s.failAt {
		return 0, errMediaRead
	}
	available := s.failAt - s.offset
	if remaining := int64(len(s.data)) - s.offset; remaining < available {
		available = remaining
	}
	if available <= 0 {
		return 0, io.EOF
	}
	if int64(len(p)) > available {
		p = p[:available]
	}
	n := copy(p, s.data[s.offset:s.offset+int64(len(p))])
	s.offset += int64(n)
	return n, nil
}

func (s *shortMediaStream) Seek(offset int64, whence int) (int64, error) {
	var target int64
	switch whence {
	case io.SeekStart:
		target = offset
	case io.SeekCurrent:
		target = s.offset + offset
	case io.SeekEnd:
		target = int64(len(s.data)) + offset
	default:
		return 0, errors.New("invalid whence")
	}
	if target < 0 || target > int64(len(s.data)) {
		return 0, errors.New("seek out of bounds")
	}
	s.offset = target
	return target, nil
}

func (s *shortMediaStream) Close() error { return nil }

func TestServeMediaStreamKeepsCommittedSuccessStatusOnReadFailure(t *testing.T) {
	data := []byte("abcdefghij")
	stream := &shortMediaStream{data: data, failAt: 5}
	response := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, "/stream", nil)

	serveMediaStream(response, request, "movie.mkv", stream)

	if response.Code != http.StatusOK {
		t.Fatalf("status = %d, want already-committed 200", response.Code)
	}
	if got := response.Header().Get("Content-Length"); got != strconv.Itoa(len(data)) {
		t.Fatalf("Content-Length = %q, want %d", got, len(data))
	}
	if got := response.Body.String(); got != "abcde" {
		t.Fatalf("body = %q, want verified prefix only", got)
	}
	if strings.Contains(response.Body.String(), `"error"`) {
		t.Fatal("read failure must not append a JSON error to an already-started media body")
	}
}

func TestServeMediaStreamRangeKeepsDeclaredRangeAndVerifiedPrefix(t *testing.T) {
	data := []byte("abcdefghij")
	stream := &shortMediaStream{data: data, failAt: 5}
	response := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, "/stream", nil)
	request.Header.Set("Range", "bytes=2-8")

	serveMediaStream(response, request, "movie.mkv", stream)

	if response.Code != http.StatusPartialContent {
		t.Fatalf("status = %d, want 206", response.Code)
	}
	if got := response.Header().Get("Content-Length"); got != "7" {
		t.Fatalf("Content-Length = %q, want range length 7", got)
	}
	if got := response.Body.String(); got != "cde" {
		t.Fatalf("body = %q, want verified range prefix only", got)
	}
}

func TestHandleStreamReturnsGatewayErrorBeforeHeadersAreCommitted(t *testing.T) {
	registry := newSessionRegistry(1, time.Minute)
	defer registry.closeAll()
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	registry.sessions["closed"] = &engineSession{id: "closed", ctx: ctx, cancel: func() {}}
	api := newAPIServer(registry, "http://127.0.0.1:8191", "test-management-token-at-least-32-bytes")

	response := httptest.NewRecorder()
	api.routes().ServeHTTP(response, httptest.NewRequest(http.MethodGet, "/v1/sessions/closed/stream", nil))

	if response.Code != http.StatusBadGateway {
		t.Fatalf("status = %d, want 502 before streaming starts", response.Code)
	}
	if got := response.Body.String(); !strings.Contains(got, "failed to prepare NZB media") {
		t.Fatalf("error body = %q, want preparation failure", got)
	}
}
