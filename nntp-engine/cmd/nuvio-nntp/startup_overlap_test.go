package main

import (
	"context"
	"net"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"
)

func TestProviderConnectionStartsBeforeNZBDownloadFinishes(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	connected := make(chan struct{})
	done := make(chan struct{})
	var connectedOnce sync.Once
	go func() {
		defer close(done)
		for {
			connection, acceptErr := listener.Accept()
			if acceptErr != nil {
				return
			}
			connectedOnce.Do(func() { close(connected) })
			go serveMissingNNTPConnection(connection)
		}
	}()
	defer func() {
		_ = listener.Close()
		<-done
	}()

	overlapped := make(chan bool, 1)
	nzbServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, request *http.Request) {
		select {
		case <-connected:
			overlapped <- true
		case <-request.Context().Done():
			overlapped <- false
		case <-time.After(3 * time.Second):
			overlapped <- false
		}
		// A failed NZB load also verifies that the early provider lease is released.
		w.WriteHeader(http.StatusNoContent)
	}))
	defer nzbServer.Close()
	registry := newSessionRegistry(1, time.Minute)
	defer registry.closeAll()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, err = registry.createContext(ctx, createSessionRequest{
		NZBURL:  nzbServer.URL,
		Servers: []string{"nntp://user:password@" + listener.Addr().String() + "/1"},
	})
	if err == nil {
		t.Fatal("empty NZB should fail after releasing its provider lease")
	}
	select {
	case ok := <-overlapped:
		if !ok {
			t.Fatal("provider connection waited for the NZB download to finish")
		}
	default:
		t.Fatal("NZB download was not attempted")
	}
	registry.providers.mu.Lock()
	defer registry.providers.mu.Unlock()
	for _, entry := range registry.providers.entries {
		if entry.refs != 0 {
			t.Fatalf("failed startup leaked %d provider leases", entry.refs)
		}
	}
}
