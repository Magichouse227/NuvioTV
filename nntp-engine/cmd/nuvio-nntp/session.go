package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"streamnzb/pkg/core/logger"
	"streamnzb/pkg/media/loader"
	"streamnzb/pkg/media/nzb"
	"streamnzb/pkg/media/unpack"
	"streamnzb/pkg/usenet/nntp"
	usenetpool "streamnzb/pkg/usenet/pool"
)

const (
	maxNZBSize                = 64 << 20
	totalSegmentCacheMB       = 64
	providerValidationTimeout = 15 * time.Second
	mediaPreparationTimeout   = 75 * time.Second
	sessionTombstoneTTL       = time.Minute
)

var defaultNZBRateLimiter = newNZBRateLimiter(time.Now)
var defaultNZBDownloadCoordinator = newNZBDownloadCoordinator(defaultNZBRateLimiter)

type createSessionRequest struct {
	NZBURL          string   `json:"nzbUrl"`
	Servers         []string `json:"servers"`
	SessionID       string   `json:"sessionId,omitempty"`
	FileIndex       *int     `json:"fileIdx,omitempty"`
	FileMustInclude string   `json:"fileMustInclude,omitempty"`
	Season          int      `json:"season,omitempty"`
	Episode         int      `json:"episode,omitempty"`
}

type engineSession struct {
	id       string
	created  time.Time
	accessed atomic.Int64

	ctx       context.Context
	cancel    context.CancelFunc
	clients   []*nntp.ClientPool
	providers *providerClientLease
	fetcher   *usenetpool.Pool
	cache     usenetpool.SegmentCache
	files     []*loader.File
	document  *nzb.NZB
	target    unpack.EpisodeTarget

	openMu            sync.Mutex
	blueprint         unpack.Blueprint
	closeOnce         sync.Once
	diagnosticCreated atomic.Bool
}

func newEngineSession(request createSessionRequest, httpClient *http.Client, providerCache *providerClientCache, cacheBudget *usenetpool.SegmentCacheBudget) (*engineSession, error) {
	return newEngineSessionContext(context.Background(), request, httpClient, providerCache, cacheBudget)
}

func newEngineSessionContext(
	startupCtx context.Context,
	request createSessionRequest,
	httpClient *http.Client,
	providerCache *providerClientCache,
	cacheBudget *usenetpool.SegmentCacheBudget,
) (*engineSession, error) {
	return newEngineSessionContextWithCoordinator(
		startupCtx,
		request,
		httpClient,
		providerCache,
		cacheBudget,
		defaultNZBDownloadCoordinator,
	)
}

func newEngineSessionContextWithCoordinator(
	startupCtx context.Context,
	request createSessionRequest,
	httpClient *http.Client,
	providerCache *providerClientCache,
	cacheBudget *usenetpool.SegmentCacheBudget,
	coordinator *nzbDownloadCoordinator,
) (*engineSession, error) {
	if coordinator == nil {
		coordinator = defaultNZBDownloadCoordinator
	}
	startupStarted := time.Now()
	if err := startupCtx.Err(); err != nil {
		return nil, err
	}
	id := request.SessionID
	if id == "" {
		var err error
		id, err = newSessionID()
		if err != nil {
			return nil, fmt.Errorf("failed to create session")
		}
	} else if !validSessionID(id) {
		return nil, errInvalidSessionID
	}
	providers, err := parseProviders(request.Servers)
	if err != nil {
		return nil, err
	}

	loadStarted := time.Now()
	document, loadMetrics, err := downloadAndParseNZBContext(startupCtx, request.NZBURL, httpClient, coordinator)
	if err != nil {
		return nil, err
	}
	logger.Info(
		"NNTP session startup phase",
		"session", id,
		"phase", "load_nzb",
		"duration_ms", time.Since(loadStarted).Milliseconds(),
		"headers_ms", loadMetrics.headers.Milliseconds(),
		"body_and_parse_ms", loadMetrics.bodyAndParse.Milliseconds(),
		"bytes", loadMetrics.bytes,
	)

	selectStarted := time.Now()
	if strings.TrimSpace(request.FileMustInclude) != "" {
		if _, err := compileFilePattern(request.FileMustInclude); err != nil {
			return nil, err
		}
	}

	contentSeason, contentEpisode := request.Season, request.Episode
	if request.FileIndex != nil || strings.TrimSpace(request.FileMustInclude) != "" {
		contentSeason, contentEpisode = 0, 0
	}
	contentFiles := document.GetSessionContentFilesForEpisode(contentSeason, contentEpisode, 0)
	logStartupPhase(id, "select_nzb_content", selectStarted)
	if len(contentFiles) == 0 {
		return nil, fmt.Errorf("NZB contains no playable content")
	}

	providerStarted := time.Now()
	providerLease, err := providerCache.acquire(providers)
	if err != nil {
		return nil, err
	}
	leaseOwned := true
	defer func() {
		if leaseOwned {
			providerLease.release()
		}
	}()

	providerWaitStarted := time.Now()
	if err := providerLease.awaitReadyContext(startupCtx); err != nil {
		return nil, err
	}
	logger.Info(
		"NNTP session startup phase",
		"session", id,
		"phase", "authenticate_providers",
		"elapsed_ms", time.Since(providerStarted).Milliseconds(),
		"validation_ms", providerLease.validationDuration().Milliseconds(),
		"wait_ms", time.Since(providerWaitStarted).Milliseconds(),
		"reused", providerLease.reused,
	)
	clients := providerLease.clients()
	segmentCache := usenetpool.NewMemorySegmentCacheWithBudget(
		cacheBudget,
	)
	pool, err := usenetpool.NewPool(&usenetpool.Config{
		Providers:    providerLease.configs(),
		SegmentCache: segmentCache,
	})
	if err != nil {
		return nil, fmt.Errorf("failed to initialize NNTP providers")
	}

	if err := startupCtx.Err(); err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(context.Background())
	target := unpack.EpisodeTarget{
		Season:          request.Season,
		Episode:         request.Episode,
		FileMustInclude: request.FileMustInclude,
	}
	if request.FileIndex != nil {
		target.HasFileIndex = true
		target.FileIndex = *request.FileIndex
	}
	fetcher := pool.SubsetForLease(id, nil, nil)
	estimator := loader.NewSegmentSizeEstimator()
	files := make([]*loader.File, 0, len(contentFiles))
	for _, info := range contentFiles {
		file := loader.NewFile(ctx, info.File, estimator, fetcher)
		file.SetOwnerSessionID(id)
		files = append(files, file)
	}

	now := time.Now()
	session := &engineSession{
		id:        id,
		created:   now,
		ctx:       ctx,
		cancel:    cancel,
		clients:   clients,
		providers: providerLease,
		fetcher:   fetcher,
		cache:     segmentCache,
		files:     files,
		document:  document,
		target:    target,
	}
	session.touch()

	prepareCtx, prepareCancel := context.WithTimeout(startupCtx, mediaPreparationTimeout)
	type mediaPreparationResult struct {
		err      error
		duration time.Duration
	}
	prepareDone := make(chan mediaPreparationResult, 1)
	prepareStarted := time.Now()
	go func() {
		stream, _, _, prepareErr := session.openMedia(prepareCtx)
		if stream != nil {
			_ = stream.Close()
		}
		prepareDone <- mediaPreparationResult{err: prepareErr, duration: time.Since(prepareStarted)}
	}()

	preflightStarted := time.Now()
	exists, statErr := verifyRequiredArchivesExist(startupCtx, files)
	logStartupPhase(id, "preflight", preflightStarted)
	switch {
	case errors.Is(statErr, errFirstSegmentUnavailable):
		prepareCancel()
		<-prepareDone
		session.close()
		logger.Warn("NNTP release rejected during preflight", "session", id, "err", statErr)
		return nil, statErr
	case statErr != nil:
		logger.Warn("NNTP preflight inconclusive; continuing", "session", id, "err", statErr)
	case !exists:
		prepareCancel()
		<-prepareDone
		session.close()
		err := fmt.Errorf("archive volume segment unavailable: %w", errFirstSegmentUnavailable)
		logger.Warn("NNTP release rejected during preflight", "session", id, "err", err)
		return nil, err
	}
	prepareResult := <-prepareDone
	prepareCancel()
	logger.Info("NNTP session startup phase", "session", id, "phase", "prepare_media", "duration_ms", prepareResult.duration.Milliseconds())
	if prepareResult.err != nil {
		session.close()
		return nil, fmt.Errorf("failed to prepare NZB media: %w", prepareResult.err)
	}
	if err := startupCtx.Err(); err != nil {
		session.close()
		return nil, err
	}
	leaseOwned = false
	session.diagnosticCreated.Store(true)
	log.Printf("NUVIO_DIAG event=session_created")
	logger.Info("NNTP session ready", "session", id, "duration_ms", time.Since(startupStarted).Milliseconds(), "files", len(files))
	return session, nil
}

func logStartupPhase(sessionID, phase string, started time.Time) {
	logger.Info("NNTP session startup phase", "session", sessionID, "phase", phase, "duration_ms", time.Since(started).Milliseconds())
}

type providerValidationResult struct {
	index int
	err   error
}

func validateProviderClients(clients []*nntp.ClientPool) error {
	ctx, cancel := context.WithTimeout(context.Background(), providerValidationTimeout)
	defer cancel()

	results := make(chan providerValidationResult, len(clients))
	for index, client := range clients {
		go func() {
			results <- providerValidationResult{index: index, err: client.ValidateContext(ctx)}
		}()
	}

	errs := make([]error, len(clients))
	for range clients {
		select {
		case result := <-results:
			if result.err == nil {
				return nil
			}
			errs[result.index] = result.err
		case <-ctx.Done():
			for index, err := range errs {
				if err != nil {
					return fmt.Errorf("NNTP provider %d connection failed: %w", index+1, err)
				}
			}
			return fmt.Errorf("NNTP provider validation failed: %w", ctx.Err())
		}
	}

	for index, err := range errs {
		if err != nil {
			return fmt.Errorf("NNTP provider %d connection failed: %w", index+1, err)
		}
	}
	return fmt.Errorf("no NNTP providers available")
}

func (s *engineSession) openMedia(ctx context.Context) (unpack.ReadSeekCloser, string, int64, error) {
	s.openMu.Lock()
	defer s.openMu.Unlock()
	if s.ctx.Err() != nil {
		return nil, "", 0, fmt.Errorf("session is closed")
	}

	files := make([]unpack.UnpackableFile, len(s.files))
	for index := range s.files {
		files[index] = s.files[index]
	}
	password := ""
	if s.document != nil {
		password = s.document.Password()
	}
	stream, name, size, blueprint, err := unpack.GetMediaStreamForEpisode(
		ctx,
		files,
		s.blueprint,
		password,
		s.target,
	)
	if err != nil {
		return nil, "", 0, err
	}
	s.blueprint = blueprint
	s.touch()
	return stream, name, size, nil
}

func (s *engineSession) stats() sessionStats {
	totalBytes := s.fetcher.StreamDownloadedBytes()
	speedMbps := s.fetcher.StreamSpeed()
	var activeConnections int
	for _, client := range s.clients {
		activeConnections += client.ActiveConnections()
	}
	return sessionStats{
		DownloadedBytes: totalBytes,
		DownloadSpeed:   int64(speedMbps * 1024 * 1024 / 8),
		Connections:     activeConnections,
	}
}

func (s *engineSession) touch() {
	s.accessed.Store(time.Now().UnixNano())
}

func (s *engineSession) lastAccess() time.Time {
	return time.Unix(0, s.accessed.Load())
}

func (s *engineSession) close() {
	s.closeOnce.Do(func() {
		s.cancel()
		s.providers.release()
		if s.cache != nil {
			s.cache.Purge()
		}
		if s.diagnosticCreated.Load() {
			log.Printf("NUVIO_DIAG event=session_closed")
		}
	})
}

func shutdownClients(clients []*nntp.ClientPool) {
	for _, client := range clients {
		client.Shutdown()
	}
}

type nzbLoadMetrics struct {
	headers      time.Duration
	bodyAndParse time.Duration
	bytes        int64
}

type countingReader struct {
	reader io.Reader
	read   int64
}

func (r *countingReader) Read(buffer []byte) (int, error) {
	read, err := r.reader.Read(buffer)
	r.read += int64(read)
	return read, err
}

func downloadAndParseNZB(rawURL string, client *http.Client) (*nzb.NZB, nzbLoadMetrics, error) {
	return downloadAndParseNZBContext(context.Background(), rawURL, client, defaultNZBDownloadCoordinator)
}

func downloadAndParseNZBContext(
	parent context.Context,
	rawURL string,
	client *http.Client,
	coordinator *nzbDownloadCoordinator,
) (*nzb.NZB, nzbLoadMetrics, error) {
	var metrics nzbLoadMetrics
	trimmed := strings.TrimSpace(rawURL)
	if _, err := nzbEndpointKey(trimmed); err != nil {
		return nil, metrics, err
	}
	ctx, cancel := context.WithTimeout(parent, 60*time.Second)
	defer cancel()
	fetched, err := coordinator.download(ctx, trimmed, client)
	metrics.headers = fetched.headerDuration
	if err != nil {
		return nil, metrics, err
	}

	bodyStarted := time.Now()
	counted := &countingReader{reader: bytes.NewReader(fetched.body)}
	document, parseErr := nzb.ParseWithContext(ctx, counted)
	if parseErr == nil {
		_, parseErr = io.Copy(io.Discard, counted)
	}
	metrics.bodyAndParse = time.Since(bodyStarted)
	metrics.bytes = counted.read
	if counted.read == 0 {
		return nil, metrics, fmt.Errorf("downloaded NZB is empty")
	}
	if counted.read > maxNZBSize {
		return nil, metrics, fmt.Errorf("downloaded NZB exceeds the 64 MiB limit")
	}
	if parseErr != nil {
		if ctx.Err() != nil {
			return nil, metrics, ctx.Err()
		}
		return nil, metrics, fmt.Errorf("failed to parse NZB")
	}
	return document, metrics, nil
}

func newSessionID() (string, error) {
	buffer := make([]byte, 16)
	if _, err := rand.Read(buffer); err != nil {
		return "", err
	}
	return hex.EncodeToString(buffer), nil
}

var (
	errInvalidSessionID        = errors.New("invalid session ID")
	errSessionIDConflict       = errors.New("session ID is already in use")
	errSessionCreationCanceled = errors.New("session creation canceled")
)

func validSessionID(id string) bool {
	if len(id) != 32 {
		return false
	}
	for _, character := range id {
		if (character < '0' || character > '9') && (character < 'a' || character > 'f') {
			return false
		}
	}
	return true
}

type sessionCreation struct {
	generation uint64
	cancel     context.CancelFunc
}

type sessionRegistry struct {
	mu           sync.RWMutex
	sessions     map[string]*engineSession
	creating     map[string]sessionCreation
	tombstones   map[string]time.Time
	generation   uint64
	maxSessions  int
	ttl          time.Duration
	now          func() time.Time
	httpClient   *http.Client
	providers    *providerClientCache
	cacheBudget  *usenetpool.SegmentCacheBudget
	nzbDownloads *nzbDownloadCoordinator
	newSession   func(context.Context, createSessionRequest) (*engineSession, error)
	stopCh       chan struct{}
	stopOnce     sync.Once
}

func newSessionRegistry(maxSessions int, ttl time.Duration) *sessionRegistry {
	client := &http.Client{
		Timeout: 65 * time.Second,
		CheckRedirect: func(request *http.Request, via []*http.Request) error {
			if len(via) >= 5 {
				return fmt.Errorf("too many redirects")
			}
			if request.URL.Scheme != "http" && request.URL.Scheme != "https" {
				return fmt.Errorf("unsupported redirect")
			}
			return nil
		},
	}
	registry := &sessionRegistry{
		sessions:     make(map[string]*engineSession),
		creating:     make(map[string]sessionCreation),
		tombstones:   make(map[string]time.Time),
		maxSessions:  maxSessions,
		ttl:          ttl,
		now:          time.Now,
		httpClient:   client,
		providers:    newProviderClientCache(),
		cacheBudget:  usenetpool.NewSegmentCacheBudget(totalSegmentCacheMB),
		nzbDownloads: defaultNZBDownloadCoordinator,
		stopCh:       make(chan struct{}),
	}
	registry.newSession = func(startupCtx context.Context, request createSessionRequest) (*engineSession, error) {
		return newEngineSessionContextWithCoordinator(
			startupCtx,
			request,
			registry.httpClient,
			registry.providers,
			registry.cacheBudget,
			registry.nzbDownloads,
		)
	}
	go registry.cleanupLoop()
	return registry
}

func (r *sessionRegistry) create(request createSessionRequest) (*engineSession, error) {
	return r.createContext(context.Background(), request)
}

func (r *sessionRegistry) createContext(ctx context.Context, request createSessionRequest) (*engineSession, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	id := request.SessionID
	if id == "" {
		var err error
		id, err = newSessionID()
		if err != nil {
			return nil, fmt.Errorf("failed to create session")
		}
		request.SessionID = id
	} else if !validSessionID(id) {
		return nil, errInvalidSessionID
	}

	startupCtx, startupCancel := context.WithCancel(ctx)
	r.mu.Lock()
	r.pruneTombstonesLocked(r.now())
	if _, exists := r.sessions[id]; exists {
		r.mu.Unlock()
		startupCancel()
		return nil, errSessionIDConflict
	}
	if _, exists := r.creating[id]; exists {
		r.mu.Unlock()
		startupCancel()
		return nil, errSessionIDConflict
	}
	if _, canceled := r.tombstones[id]; canceled {
		r.mu.Unlock()
		startupCancel()
		return nil, errSessionCreationCanceled
	}
	r.generation++
	generation := r.generation
	r.creating[id] = sessionCreation{
		generation: generation,
		cancel:     startupCancel,
	}
	r.mu.Unlock()

	buildSession := r.newSession
	if buildSession == nil {
		buildSession = func(startupCtx context.Context, request createSessionRequest) (*engineSession, error) {
			return newEngineSessionContextWithCoordinator(
				startupCtx,
				request,
				r.httpClient,
				r.providers,
				r.cacheBudget,
				r.nzbDownloads,
			)
		}
	}
	session, err := buildSession(startupCtx, request)
	if err != nil {
		r.finishCreate(id, generation)
		startupCancel()
		return nil, err
	}
	session.id = id

	r.mu.Lock()
	reservation, reserved := r.creating[id]
	allowed := reserved &&
		reservation.generation == generation &&
		startupCtx.Err() == nil &&
		!r.tombstoneActiveLocked(id, r.now()) &&
		r.sessions[id] == nil
	if reserved && reservation.generation == generation {
		delete(r.creating, id)
	}
	var evicted []*engineSession
	if allowed {
		r.sessions[id] = session
		evicted = r.evictLocked(id)
	}
	r.mu.Unlock()
	startupCancel()
	if !allowed {
		session.close()
		return nil, errSessionCreationCanceled
	}
	for _, old := range evicted {
		old.close()
	}
	return session, nil
}

func (r *sessionRegistry) finishCreate(id string, generation uint64) {
	r.mu.Lock()
	if reservation, ok := r.creating[id]; ok && reservation.generation == generation {
		delete(r.creating, id)
	}
	r.mu.Unlock()
}

func (r *sessionRegistry) get(id string, touch bool) (*engineSession, bool) {
	r.mu.RLock()
	session, ok := r.sessions[id]
	r.mu.RUnlock()
	if ok && touch {
		session.touch()
	}
	return session, ok
}

func (r *sessionRegistry) delete(id string) bool {
	if !validSessionID(id) {
		return false
	}
	var pendingCancel context.CancelFunc
	r.mu.Lock()
	r.pruneTombstonesLocked(r.now())
	session, hasSession := r.sessions[id]
	if hasSession {
		delete(r.sessions, id)
	}
	if pending, pendingOK := r.creating[id]; pendingOK {
		delete(r.creating, id)
		pendingCancel = pending.cancel
	}
	r.tombstones[id] = r.now().Add(sessionTombstoneTTL)
	r.mu.Unlock()
	if pendingCancel != nil {
		pendingCancel()
	}
	if session != nil {
		session.close()
	}
	return true
}

func (r *sessionRegistry) pruneTombstonesLocked(now time.Time) {
	for id, expires := range r.tombstones {
		if !expires.After(now) {
			delete(r.tombstones, id)
		}
	}
}

func (r *sessionRegistry) tombstoneActiveLocked(id string, now time.Time) bool {
	expires, ok := r.tombstones[id]
	if !ok {
		return false
	}
	if !expires.After(now) {
		delete(r.tombstones, id)
		return false
	}
	return true
}

func (r *sessionRegistry) evictLocked(keepID string) []*engineSession {
	now := time.Now()
	type candidate struct {
		id      string
		session *engineSession
	}
	candidates := make([]candidate, 0, len(r.sessions))
	for id, session := range r.sessions {
		if id != keepID && now.Sub(session.lastAccess()) > r.ttl {
			delete(r.sessions, id)
			candidates = append(candidates, candidate{id: id, session: session})
		}
	}
	if len(r.sessions) > r.maxSessions {
		remaining := make([]candidate, 0, len(r.sessions))
		for id, session := range r.sessions {
			if id != keepID {
				remaining = append(remaining, candidate{id: id, session: session})
			}
		}
		sort.Slice(remaining, func(i, j int) bool {
			return remaining[i].session.lastAccess().Before(remaining[j].session.lastAccess())
		})
		for _, item := range remaining {
			if len(r.sessions) <= r.maxSessions {
				break
			}
			delete(r.sessions, item.id)
			candidates = append(candidates, item)
		}
	}

	evicted := make([]*engineSession, 0, len(candidates))
	seen := make(map[string]bool)
	for _, item := range candidates {
		if !seen[item.id] {
			seen[item.id] = true
			evicted = append(evicted, item.session)
		}
	}
	return evicted
}

func (r *sessionRegistry) closeAll() {
	r.stopOnce.Do(func() { close(r.stopCh) })
	r.mu.Lock()
	sessions := make([]*engineSession, 0, len(r.sessions))
	for id, session := range r.sessions {
		delete(r.sessions, id)
		sessions = append(sessions, session)
	}
	pending := make([]context.CancelFunc, 0, len(r.creating))
	for id, reservation := range r.creating {
		delete(r.creating, id)
		pending = append(pending, reservation.cancel)
	}
	r.mu.Unlock()
	for _, cancel := range pending {
		cancel()
	}
	for _, session := range sessions {
		session.close()
	}
	r.providers.closeAll()
}

func (r *sessionRegistry) cleanupLoop() {
	interval := time.Minute
	if r.ttl > 0 && r.ttl/4 < interval {
		interval = r.ttl / 4
	}
	if interval <= 0 {
		interval = time.Minute
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ticker.C:
			r.cleanupExpired()
		case <-r.stopCh:
			return
		}
	}
}

func (r *sessionRegistry) cleanupExpired() {
	now := r.now()
	r.mu.Lock()
	r.pruneTombstonesLocked(now)
	expired := make([]*engineSession, 0)
	if r.ttl > 0 {
		for id, session := range r.sessions {
			if now.Sub(session.lastAccess()) > r.ttl {
				delete(r.sessions, id)
				expired = append(expired, session)
			}
		}
	}
	r.mu.Unlock()
	for _, session := range expired {
		session.close()
	}
	r.providers.cleanupIdle(now)
}
