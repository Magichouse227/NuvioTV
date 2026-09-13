package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	usenetpool "streamnzb/pkg/usenet/pool"
)

const validNZB = `<?xml version="1.0"?><nzb><file subject="Movie.mkv"><groups><group>alt.binaries.test</group></groups><segments><segment bytes="10" number="1">message-id</segment></segments></file></nzb>`

func TestDownloadAndParseNZB(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, _ *http.Request) {
		_, _ = writer.Write([]byte(validNZB))
	}))
	defer server.Close()

	document, metrics, err := downloadAndParseNZB(server.URL, server.Client())
	if err != nil {
		t.Fatalf("downloadAndParseNZB: %v", err)
	}
	if metrics.bytes != int64(len(validNZB)) {
		t.Fatalf("bytes = %d, want %d", metrics.bytes, len(validNZB))
	}
	if len(document.Files) != 1 || document.Files[0].Subject != "Movie.mkv" {
		t.Fatalf("unexpected parsed files: %+v", document.Files)
	}
}

func TestDownloadAndParseNZBRejectsEmptyResponse(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, _ *http.Request) {}))
	defer server.Close()

	_, _, err := downloadAndParseNZB(server.URL, server.Client())
	if err == nil || !strings.Contains(err.Error(), "empty") {
		t.Fatalf("error = %v, want empty NZB error", err)
	}
}

func TestDownloadAndParseNZBRejectsOversizedContentLength(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, _ *http.Request) {
		writer.Header().Set("Content-Length", "67108865")
		writer.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	_, _, err := downloadAndParseNZB(server.URL, server.Client())
	if err == nil || !strings.Contains(err.Error(), "64 MiB") {
		t.Fatalf("error = %v, want size limit error", err)
	}
}

func TestSessionRegistryUsesOneBoundedSegmentCacheBudget(t *testing.T) {
	registry := newSessionRegistry(2, time.Minute)
	defer registry.closeAll()

	if got := registry.cacheBudget.MaxBytes(); got != int64(totalSegmentCacheMB<<20) {
		t.Fatalf("shared cache budget = %d, want %d", got, int64(totalSegmentCacheMB<<20))
	}
	limit := registry.cacheBudget.MaxBytes()
	if !registry.cacheBudget.Reserve(limit) {
		t.Fatal("shared cache budget did not reserve its exact limit")
	}
	if registry.cacheBudget.Reserve(1) {
		t.Fatal("shared cache budget exceeded its configured total limit")
	}
	registry.cacheBudget.Release(limit)
	first := usenetpool.NewMemorySegmentCacheWithBudget(registry.cacheBudget)
	second := usenetpool.NewMemorySegmentCacheWithBudget(registry.cacheBudget)
	first.Set("first", usenetpool.SegmentData{Body: []byte("abc")})
	second.Set("second", usenetpool.SegmentData{Body: []byte("de")})
	if got := registry.cacheBudget.CurrentBytes(); got != 5 {
		t.Fatalf("shared cache usage = %d, want 5 across both sessions", got)
	}

	first.Purge()
	if got := registry.cacheBudget.CurrentBytes(); got != 2 {
		t.Fatalf("purging one session released %d bytes, want other session retained", 5-got)
	}
	second.Purge()
}
