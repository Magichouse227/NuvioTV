package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"
)

const testSessionID = "0123456789abcdef0123456789abcdef"

func testRegistrySession(id string) *engineSession {
	ctx, cancel := context.WithCancel(context.Background())
	session := &engineSession{id: id, ctx: ctx, cancel: cancel, created: time.Now()}
	session.touch()
	return session
}

func TestSessionIDValidationAndDeleteBeforePost(t *testing.T) {
	if !validSessionID(testSessionID) {
		t.Fatal("test session ID must be valid")
	}
	for _, invalid := range []string{
		"",
		"0123456789abcdef0123456789abcde",
		"0123456789abcdef0123456789abcdef0",
		"0123456789abcdef0123456789ABCDE",
		"0123456789abcdef0123456789abcdeg",
	} {
		if validSessionID(invalid) {
			t.Fatalf("validSessionID(%q) = true", invalid)
		}
	}

	registry := newSessionRegistry(2, time.Minute)
	defer registry.closeAll()
	api := newAPIServer(registry, "http://127.0.0.1:8191", "test-management-token-at-least-32-bytes")
	handler := api.routes()

	deleteRequest := httptest.NewRequest(http.MethodDelete, "/v1/sessions/"+testSessionID, nil)
	deleteRequest.Header.Set(managementTokenHeader, "test-management-token-at-least-32-bytes")
	deleteResponse := httptest.NewRecorder()
	handler.ServeHTTP(deleteResponse, deleteRequest)
	if deleteResponse.Code != http.StatusNoContent {
		t.Fatalf("DELETE-before-POST status = %d, want 204", deleteResponse.Code)
	}

	body, err := json.Marshal(createSessionRequest{SessionID: testSessionID})
	if err != nil {
		t.Fatal(err)
	}
	postRequest := httptest.NewRequest(http.MethodPost, "/v1/sessions", bytes.NewReader(body))
	postRequest.Header.Set(managementTokenHeader, "test-management-token-at-least-32-bytes")
	postResponse := httptest.NewRecorder()
	handler.ServeHTTP(postResponse, postRequest)
	if postResponse.Code != http.StatusConflict {
		t.Fatalf("POST after DELETE status = %d, want 409", postResponse.Code)
	}
	registry.mu.RLock()
	defer registry.mu.RUnlock()
	if len(registry.sessions) != 0 || len(registry.creating) != 0 {
		t.Fatalf("canceled ID retained sessions=%d creating=%d", len(registry.sessions), len(registry.creating))
	}
}

func TestDeleteCancelsSessionCreationAndPreventsCommit(t *testing.T) {
	registry := newSessionRegistry(2, time.Minute)
	defer registry.closeAll()
	entered := make(chan struct{})
	registry.newSession = func(ctx context.Context, request createSessionRequest) (*engineSession, error) {
		close(entered)
		<-ctx.Done()
		return nil, ctx.Err()
	}

	result := make(chan error, 1)
	go func() {
		_, err := registry.createContext(context.Background(), createSessionRequest{SessionID: testSessionID})
		result <- err
	}()
	<-entered
	if !registry.delete(testSessionID) {
		t.Fatal("delete did not accept valid pending session ID")
	}
	if err := <-result; !errors.Is(err, context.Canceled) {
		t.Fatalf("create error = %v, want context cancellation", err)
	}
	registry.mu.RLock()
	defer registry.mu.RUnlock()
	if len(registry.sessions) != 0 || len(registry.creating) != 0 {
		t.Fatalf("canceled creation retained sessions=%d creating=%d", len(registry.sessions), len(registry.creating))
	}
}

func TestDuplicateSessionIDCannotCreateSecondSession(t *testing.T) {
	registry := newSessionRegistry(2, time.Minute)
	defer registry.closeAll()
	entered := make(chan struct{})
	release := make(chan struct{})
	registry.newSession = func(ctx context.Context, request createSessionRequest) (*engineSession, error) {
		close(entered)
		select {
		case <-release:
			return testRegistrySession(request.SessionID), nil
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}

	firstResult := make(chan error, 1)
	go func() {
		_, err := registry.createContext(context.Background(), createSessionRequest{SessionID: testSessionID})
		firstResult <- err
	}()
	<-entered
	if _, err := registry.createContext(context.Background(), createSessionRequest{SessionID: testSessionID}); !errors.Is(err, errSessionIDConflict) {
		t.Fatalf("duplicate create error = %v, want ID conflict", err)
	}
	close(release)
	if err := <-firstResult; err != nil {
		t.Fatalf("first create error = %v", err)
	}
	if !registry.delete(testSessionID) {
		t.Fatal("failed to delete first session")
	}
}

func TestSessionSurvivesCreateRequestCancellation(t *testing.T) {
	registry := newSessionRegistry(2, time.Minute)
	defer registry.closeAll()
	registry.newSession = func(_ context.Context, request createSessionRequest) (*engineSession, error) {
		return testRegistrySession(request.SessionID), nil
	}
	requestContext, cancel := context.WithCancel(context.Background())
	session, err := registry.createContext(requestContext, createSessionRequest{SessionID: testSessionID})
	if err != nil {
		t.Fatal(err)
	}
	cancel()
	if session.ctx.Err() != nil {
		t.Fatalf("session context canceled with POST context: %v", session.ctx.Err())
	}
	if got, ok := registry.get(testSessionID, false); !ok || got != session {
		t.Fatal("successful session was not retained after request cancellation")
	}
}

type blockingResponseWriter struct {
	header  http.Header
	started chan struct{}
	release chan struct{}
	status  int
	body    bytes.Buffer
	once    sync.Once
}

func newBlockingResponseWriter() *blockingResponseWriter {
	return &blockingResponseWriter{
		header:  make(http.Header),
		started: make(chan struct{}),
		release: make(chan struct{}),
	}
}

func (w *blockingResponseWriter) Header() http.Header { return w.header }

func (w *blockingResponseWriter) WriteHeader(status int) {
	w.once.Do(func() {
		w.status = status
		close(w.started)
	})
}

func (w *blockingResponseWriter) Write(data []byte) (int, error) {
	<-w.release
	return w.body.Write(data)
}

func TestDeleteAfterCommitBeforeCreateResponseRemovesSession(t *testing.T) {
	registry := newSessionRegistry(2, time.Minute)
	defer registry.closeAll()
	registry.newSession = func(_ context.Context, request createSessionRequest) (*engineSession, error) {
		return testRegistrySession(request.SessionID), nil
	}
	api := newAPIServer(registry, "http://127.0.0.1:8191", "test-management-token-at-least-32-bytes")
	handler := api.routes()
	body, err := json.Marshal(createSessionRequest{SessionID: testSessionID})
	if err != nil {
		t.Fatal(err)
	}
	postRequest := httptest.NewRequest(http.MethodPost, "/v1/sessions", bytes.NewReader(body))
	postRequest.Header.Set(managementTokenHeader, "test-management-token-at-least-32-bytes")
	postResponse := newBlockingResponseWriter()
	done := make(chan struct{})
	go func() {
		handler.ServeHTTP(postResponse, postRequest)
		close(done)
	}()
	<-postResponse.started
	if _, ok := registry.get(testSessionID, false); !ok {
		t.Fatal("session was not committed before response write")
	}

	deleteRequest := httptest.NewRequest(http.MethodDelete, "/v1/sessions/"+testSessionID, nil)
	deleteRequest.Header.Set(managementTokenHeader, "test-management-token-at-least-32-bytes")
	deleteResponse := httptest.NewRecorder()
	handler.ServeHTTP(deleteResponse, deleteRequest)
	if deleteResponse.Code != http.StatusNoContent {
		t.Fatalf("DELETE-after-commit status = %d, want 204", deleteResponse.Code)
	}
	close(postResponse.release)
	<-done
	if _, ok := registry.get(testSessionID, false); ok {
		t.Fatal("session retained after known-ID cancellation")
	}
}
