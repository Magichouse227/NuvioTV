package main

import (
	"context"
	"errors"
)

// Keep the original error for errors.Is/As (especially indexer rate limits),
// but expose only a fixed code and credential-free guidance over the API.
type sessionSetupError struct {
	code  string
	cause error
}

func (e *sessionSetupError) Error() string { return e.cause.Error() }
func (e *sessionSetupError) Unwrap() error { return e.cause }

func setupFailure(code string, err error) error {
	return &sessionSetupError{code: code, cause: err}
}

func publicSessionError(err error) errorResponse {
	var rateLimit *nzbRateLimitError
	if errors.As(err, &rateLimit) {
		return errorResponse{Code: "nzb_rate_limited", Error: "The indexer is temporarily rate limited."}
	}
	if errors.Is(err, errFirstSegmentUnavailable) {
		return errorResponse{Code: "incomplete_release", Error: "This release is missing required articles. Try another source."}
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return errorResponse{Code: "setup_timeout", Error: "NNTP setup timed out. Check the provider connection or try another source."}
	}
	var setup *sessionSetupError
	if errors.As(err, &setup) {
		switch setup.code {
		case "provider_configuration":
			return errorResponse{Code: setup.code, Error: "Check the Usenet server settings in your NZB addon."}
		case "provider_connection":
			return errorResponse{Code: setup.code, Error: "Cannot connect to the Usenet provider. Check host, TLS/port, credentials and connection limits in your addon."}
		case "nzb_load_failed":
			return errorResponse{Code: setup.code, Error: "Cannot load the NZB. Check your indexer or addon access, or try another source."}
		case "no_playable_content":
			return errorResponse{Code: setup.code, Error: "The NZB contains no playable media for this selection. Try another source."}
		}
	}
	return errorResponse{Code: "session_failed", Error: "Cannot prepare this NNTP stream. Try another source."}
}
