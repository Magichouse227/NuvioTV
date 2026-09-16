package main

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"testing"
)

func TestPublicSessionErrorsUseOnlyKnownCredentialFreeMessages(t *testing.T) {
	private := errors.New("nntps://private-user:private-password@news.invalid source?apikey=private-key")
	for _, code := range []string{
		"provider_configuration", "provider_connection", "nzb_load_failed", "no_playable_content", "unknown",
	} {
		t.Run(code, func(t *testing.T) {
			err := setupFailure(code, private)
			if !errors.Is(err, private) {
				t.Fatal("wrapping must preserve the underlying error identity")
			}
			public := publicSessionError(fmt.Errorf("wrapped: %w", err))
			expected := code
			if code == "unknown" {
				expected = "session_failed"
			}
			if public.Code != expected || public.Error == "" {
				t.Fatalf("unexpected public error: %+v", public)
			}
			if strings.Contains(public.Error, "private-") {
				t.Fatal("public API leaked credentials or provider error text")
			}
		})
	}
}

func TestPublicSessionErrorsPreserveMissingArticlesAndTimeout(t *testing.T) {
	for _, item := range []struct {
		err  error
		code string
	}{
		{fmt.Errorf("archive: %w", errFirstSegmentUnavailable), "incomplete_release"},
		{setupFailure("provider_connection", context.DeadlineExceeded), "setup_timeout"},
		{errors.New("private unknown error"), "session_failed"},
	} {
		if got := publicSessionError(item.err); got.Code != item.code {
			t.Fatalf("code = %s, want %s", got.Code, item.code)
		}
	}
}
