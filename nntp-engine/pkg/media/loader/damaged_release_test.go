package loader

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net/textproto"
	"strings"
	"sync"
	"testing"

	"streamnzb/pkg/media/nzb"
	"streamnzb/pkg/usenet/nntp"
	"streamnzb/pkg/usenet/pool"
)

// damagedSegmentFetcher serves a synthetic release whose listed articles are all
// present except the indices in missing, which answer 430 the way a provider
// does for an article that was never propagated or was removed.
type damagedSegmentFetcher struct {
	segmentSize int
	missing     map[int]struct{}

	mu      sync.Mutex
	fetches map[int]int
}

func newDamagedSegmentFetcher(segmentSize int, missing ...int) *damagedSegmentFetcher {
	gone := make(map[int]struct{}, len(missing))
	for _, idx := range missing {
		gone[idx] = struct{}{}
	}
	return &damagedSegmentFetcher{segmentSize: segmentSize, missing: gone, fetches: make(map[int]int)}
}

func (f *damagedSegmentFetcher) FetchSegment(_ context.Context, segment *nzb.Segment, _ []string) (pool.SegmentData, error) {
	index := segment.Number - 1

	f.mu.Lock()
	f.fetches[index]++
	f.mu.Unlock()

	if _, gone := f.missing[index]; gone {
		return pool.SegmentData{}, fmt.Errorf("fetch segment %s: %w", segment.ID, &textproto.Error{Code: 430, Msg: "No Such Article"})
	}
	return pool.SegmentData{Body: segmentPayload(index, f.segmentSize)}, nil
}

func (f *damagedSegmentFetcher) fetchCount(index int) int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.fetches[index]
}

// segmentPayload gives every segment a distinct, non-zero byte so a zero-filled
// gap is distinguishable from real data that merely landed at the wrong offset.
func segmentPayload(index, size int) []byte {
	return bytes.Repeat([]byte{byte(index%255 + 1)}, size)
}

func damagedNZBFile(segments, segmentSize int) *nzb.File {
	sizes := make([]int64, segments)
	for i := range sizes {
		sizes[i] = int64(segmentSize)
	}
	return testNZBFileWithSegments(sizes...)
}

// playbackCtx mirrors how a play request reads: gap probing off, so the segment
// map comes from sampling rather than downloading every article up front.
func playbackCtx() context.Context {
	return WithSkipGapProbing(context.Background(), true)
}

func strictDamagedFile(segmentCount, segmentSize int, fetcher SegmentFetcher) *File {
	estimator := NewSegmentSizeEstimator()
	estimator.Set(int64(segmentSize), int64(segmentSize))
	return NewFile(context.Background(), damagedNZBFile(segmentCount, segmentSize), estimator, fetcher)
}

func TestMissingArticleStopsStreamWithoutSyntheticBytes(t *testing.T) {
	const segments, segmentSize = 12, 1024
	fetcher := newDamagedSegmentFetcher(segmentSize, 3)
	f := strictDamagedFile(segments, segmentSize, fetcher)

	stream, err := f.OpenStreamCtx(playbackCtx())
	if err != nil {
		t.Fatalf("OpenStreamCtx returned error: %v", err)
	}
	defer stream.Close()

	got, err := io.ReadAll(stream)
	if !nntp.IsArticleNotFound(err) {
		t.Fatalf("read error = %v, want missing-article error", err)
	}
	if len(got) != 3*segmentSize {
		t.Fatalf("read %d bytes before failed segment, want %d", len(got), 3*segmentSize)
	}
	for i := 0; i < 3; i++ {
		want := segmentPayload(i, segmentSize)
		if !bytes.Equal(got[i*segmentSize:(i+1)*segmentSize], want) {
			t.Fatalf("segment %d did not preserve its source bytes", i)
		}
	}
	if f.IsFailed() != true {
		t.Fatal("a confirmed missing article must mark the source unavailable")
	}
}

func TestMissingArticleReadAtReturnsOnlyVerifiedPrefix(t *testing.T) {
	const segmentSize = 1024
	fetcher := newDamagedSegmentFetcher(segmentSize, 2)
	f := strictDamagedFile(6, segmentSize, fetcher)

	buf := bytes.Repeat([]byte{0xcc}, 3*segmentSize)
	n, err := f.ReadAt(buf, 0)
	if !nntp.IsArticleNotFound(err) {
		t.Fatalf("ReadAt error = %v, want missing-article error", err)
	}
	if n != 2*segmentSize {
		t.Fatalf("ReadAt copied %d bytes, want verified prefix %d", n, 2*segmentSize)
	}
	if !bytes.Equal(buf[:n], append(segmentPayload(0, segmentSize), segmentPayload(1, segmentSize)...)) {
		t.Fatal("ReadAt returned bytes other than the verified source prefix")
	}
	if !bytes.Equal(buf[2*segmentSize:], bytes.Repeat([]byte{0xcc}, segmentSize)) {
		t.Fatal("ReadAt wrote fabricated bytes after the failed segment")
	}
}

// transientFailureFetcher fails the indices in failing with a non-430 error —
// a timeout, a reset, an exhausted pool — the kinds of failure that say nothing
// about whether the article exists.
type transientFailureFetcher struct {
	segmentSize int
	failing     map[int]struct{}
}

func (f *transientFailureFetcher) FetchSegment(_ context.Context, segment *nzb.Segment, _ []string) (pool.SegmentData, error) {
	index := segment.Number - 1
	if _, bad := f.failing[index]; bad {
		return pool.SegmentData{}, fmt.Errorf("fetch segment %s: failed after retries: read tcp: i/o timeout", segment.ID)
	}
	return pool.SegmentData{Body: segmentPayload(index, f.segmentSize)}, nil
}

func TestTransientFetchFailureIsAnErrorNotAHole(t *testing.T) {
	const segmentSize = 1024
	fetcher := &transientFailureFetcher{segmentSize: segmentSize, failing: map[int]struct{}{2: {}}}
	f := NewFile(context.Background(), damagedNZBFile(6, segmentSize), nil, fetcher)

	_, err := f.DownloadSegment(context.Background(), 2)
	if err == nil {
		t.Fatal("a transient fetch failure must surface as an error, not zeros")
	}
	if nntp.IsArticleNotFound(err) {
		t.Fatalf("a transient failure must not read as a missing article: %v", err)
	}
	if f.IsFailed() {
		t.Fatal("a transient failure must not be classified as a confirmed missing article")
	}
}

// mismatchedSegmentFetcher serves every segment at def bytes except the
// indices in sizes — a post whose articles decode to lengths the estimated
// segment map did not predict.
type mismatchedSegmentFetcher struct {
	def   int
	sizes map[int]int
}

func (f *mismatchedSegmentFetcher) FetchSegment(_ context.Context, segment *nzb.Segment, _ []string) (pool.SegmentData, error) {
	index := segment.Number - 1
	size := f.def
	if s, ok := f.sizes[index]; ok {
		size = s
	}
	return pool.SegmentData{Body: segmentPayload(index, size)}, nil
}

// A segment that decodes longer than the map predicted used to have its
// surplus silently dropped, shifting every byte after it. It must be a loud
// error instead.
func TestSegmentLongerThanMapFailsLoudly(t *testing.T) {
	const segmentSize = 1024
	fetcher := &mismatchedSegmentFetcher{def: segmentSize, sizes: map[int]int{2: segmentSize + 76}}
	f := NewFile(context.Background(), damagedNZBFile(6, segmentSize), nil, fetcher)

	stream, err := f.OpenStreamCtx(playbackCtx())
	if err != nil {
		t.Fatalf("OpenStreamCtx returned error: %v", err)
	}
	defer stream.Close()

	_, err = io.ReadAll(stream)
	if err == nil {
		t.Fatal("a segment longer than its mapped size must error, not silently drop bytes")
	}
	if !strings.Contains(err.Error(), "mapped") {
		t.Fatalf("error should name the map mismatch, got: %v", err)
	}
}

func TestSegmentLengthMismatchFailsLoudlyOnReadAt(t *testing.T) {
	const segmentSize = 1024
	fetcher := &mismatchedSegmentFetcher{def: segmentSize, sizes: map[int]int{2: segmentSize + 76}}
	f := NewFile(context.Background(), damagedNZBFile(6, segmentSize), nil, fetcher)
	if err := f.EnsureSegmentMapCtx(playbackCtx()); err != nil {
		t.Fatalf("EnsureSegmentMapCtx returned error: %v", err)
	}

	buf := make([]byte, 3*segmentSize)
	if _, err := f.ReadAt(buf, 2*segmentSize); err == nil {
		t.Fatal("ReadAt through a mismatched segment must error, not shift bytes")
	}
}

func TestMissingFirstArticleStillFailsFast(t *testing.T) {
	const segmentSize = 1024
	fetcher := newDamagedSegmentFetcher(segmentSize, 0)
	f := NewFile(context.Background(), damagedNZBFile(6, segmentSize), nil, fetcher)

	_, err := f.DownloadSegment(context.Background(), 0)
	if !nntp.IsArticleNotFound(err) {
		t.Fatalf("DownloadSegment(0) = %v, want a missing-article error", err)
	}
	if !f.IsFailed() {
		t.Fatal("a missing header segment must mark the source unavailable")
	}
}
