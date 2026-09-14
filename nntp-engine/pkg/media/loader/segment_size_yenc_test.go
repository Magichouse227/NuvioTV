package loader

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"sync"
	"testing"

	"streamnzb/pkg/media/nzb"
	"streamnzb/pkg/usenet/pool"
)

func geoSegments(n int) []*Segment {
	segs := make([]*Segment, n)
	for i := range segs {
		segs[i] = &Segment{Segment: nzb.Segment{Number: i + 1, ID: "id", Bytes: 105}}
	}
	return segs
}

func TestExactSizesFromYencGeometry(t *testing.T) {
	segs := geoSegments(5)
	stride, last := int64(100), int64(40)
	fileSize := 4*stride + last

	t.Run("uniform_layout_is_exact", func(t *testing.T) {
		sizes, ok := exactSizesFromYencGeometry(segs,
			map[int]int64{0: stride, 4: last},
			yencGeometry{fileSize: fileSize, offsets: map[int]int64{0: 0, 4: 400}})
		if !ok {
			t.Fatal("expected exact map")
		}
		want := []int64{100, 100, 100, 100, 40}
		for i, w := range want {
			if sizes[i] != w {
				t.Fatalf("sizes[%d] = %d, want %d", i, sizes[i], w)
			}
		}
	})

	t.Run("off_grid_offset_bails", func(t *testing.T) {
		if _, ok := exactSizesFromYencGeometry(segs, nil,
			yencGeometry{fileSize: fileSize, offsets: map[int]int64{4: 399}}); ok {
			t.Fatal("non-uniform offset must fall back to measuring")
		}
	})

	t.Run("measured_length_disagreement_bails", func(t *testing.T) {
		if _, ok := exactSizesFromYencGeometry(segs,
			map[int]int64{2: 99},
			yencGeometry{fileSize: fileSize, offsets: map[int]int64{4: 400}}); ok {
			t.Fatal("a probed article contradicting the grid must fall back")
		}
	})

	t.Run("implausible_tail_bails", func(t *testing.T) {
		if _, ok := exactSizesFromYencGeometry(segs, nil,
			yencGeometry{fileSize: 5 * stride * 2, offsets: map[int]int64{4: 400}}); ok {
			t.Fatal("a tail larger than the stride must fall back")
		}
		if _, ok := exactSizesFromYencGeometry(segs, nil,
			yencGeometry{fileSize: 400, offsets: map[int]int64{4: 400}}); ok {
			t.Fatal("a zero-size tail must fall back")
		}
	})

	t.Run("poisoned_or_empty_geometry_bails", func(t *testing.T) {
		if _, ok := exactSizesFromYencGeometry(segs, nil,
			yencGeometry{fileSize: -1, offsets: map[int]int64{4: 400}}); ok {
			t.Fatal("poisoned geometry must fall back")
		}
		if _, ok := exactSizesFromYencGeometry(segs, nil,
			yencGeometry{fileSize: fileSize, offsets: map[int]int64{0: 0}}); ok {
			t.Fatal("offset 0 alone cannot pin a stride")
		}
	})
}

// geometryFetcher serves articles whose yEnc headers declare the exact layout.
type geometryFetcher struct {
	stride, last, fileSize int64
	n                      int

	mu      sync.Mutex
	fetched map[int]int
}

func (f *geometryFetcher) FetchSegment(ctx context.Context, segment *nzb.Segment, groups []string) (pool.SegmentData, error) {
	idx := int(segment.Number) - 1
	f.mu.Lock()
	if f.fetched == nil {
		f.fetched = make(map[int]int)
	}
	f.fetched[idx]++
	f.mu.Unlock()

	size := f.stride
	if idx == f.n-1 {
		size = f.last
	}
	body := make([]byte, size)
	return pool.SegmentData{
		Body:           body,
		Size:           size,
		YencFileSize:   f.fileSize,
		YencPartOffset: int64(idx) * f.stride,
	}, nil
}

func (f *geometryFetcher) fetchCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	total := 0
	for _, c := range f.fetched {
		total += c
	}
	return total
}

func newGeometryFile(n int, fetcher SegmentFetcher) *File {
	segs := make([]nzb.Segment, n)
	for i := range segs {
		segs[i] = nzb.Segment{Number: i + 1, ID: string(rune('a' + i)), Bytes: 105}
	}
	return NewFile(context.Background(), &nzb.File{Subject: "geo.mkv", Segments: segs}, nil, fetcher)
}

// With geometry in the articles, even the slow (non-skip-gap) path builds an
// exact map from the planner's two probes instead of downloading every segment.
func TestSegmentMapExactFromYencGeometrySkipsGapProbing(t *testing.T) {
	const n = 40
	fetcher := &geometryFetcher{stride: 100, last: 40, fileSize: 39*100 + 40, n: n}
	f := newGeometryFile(n, fetcher)

	if err := f.EnsureSegmentMap(); err != nil { // f.ctx: gap probing NOT skipped
		t.Fatal(err)
	}
	if got, want := f.Size(), int64(39*100+40); got != want {
		t.Fatalf("total = %d, want %d", got, want)
	}
	start, end, _ := f.SegmentOffsetRange(n - 1)
	if start != 3900 || end != 3940 {
		t.Fatalf("last segment mapped [%d,%d), want [3900,3940)", start, end)
	}
	if c := fetcher.fetchCount(); c > 3 {
		t.Fatalf("exact geometry should need the planner's probes only, fetched %d articles", c)
	}
	if data, err := f.DownloadSegment(context.Background(), 1); err != nil {
		t.Fatalf("matching yEnc geometry was rejected: %v", err)
	} else if len(data) != int(fetcher.stride) {
		t.Fatalf("matching segment length = %d, want %d", len(data), fetcher.stride)
	}
}

func TestSegmentMapExactFromKnownIrregularBoundaries(t *testing.T) {
	fetcher := &varyingGeometryFetcher{lengths: []int64{100, 99, 100}}
	f := NewFile(context.Background(), testNZBFileWithSegments(120, 120, 120), nil, fetcher)

	if err := f.EnsureSegmentMap(); err != nil {
		t.Fatalf("EnsureSegmentMap returned error: %v", err)
	}
	if got := f.Size(); got != 299 {
		t.Fatalf("mapped size = %d, want 299", got)
	}
	for i, want := range [][2]int64{{0, 100}, {100, 199}, {199, 299}} {
		start, end, ok := f.SegmentOffsetRange(i)
		if !ok || start != want[0] || end != want[1] {
			t.Fatalf("segment %d mapped [%d,%d), want [%d,%d)", i, start, end, want[0], want[1])
		}
	}
	if got := fetcher.fetchCount(); got != 2 {
		t.Fatalf("exact boundary map fetched %d articles before reading, want first/last only", got)
	}

	want := append(bytes.Repeat([]byte{'A'}, 100),
		append(bytes.Repeat([]byte{'B'}, 99), bytes.Repeat([]byte{'C'}, 100)...)...)
	stream, err := f.OpenStreamCtx(context.Background())
	if err != nil {
		t.Fatalf("OpenStreamCtx returned error: %v", err)
	}
	defer stream.Close()
	all, err := io.ReadAll(stream)
	if err != nil {
		t.Fatalf("whole-file read returned error: %v", err)
	}
	if !bytes.Equal(all, want) {
		t.Fatalf("whole-file bytes differ from the exact irregular release")
	}

	if _, err := stream.Seek(95, io.SeekStart); err != nil {
		t.Fatalf("Seek returned error: %v", err)
	}
	gotRange := make([]byte, 20)
	if _, err := io.ReadFull(stream, gotRange); err != nil {
		t.Fatalf("seeked range read returned error: %v", err)
	}
	if !bytes.Equal(gotRange, want[95:115]) {
		t.Fatalf("seeked range = %q, want %q", gotRange, want[95:115])
	}
}

// Without geometry the same file pays a full gap-probe pass on the slow path —
// the regression this feature removes.
func TestSegmentMapWithoutGeometryStillGapProbes(t *testing.T) {
	const n = 40
	fetcher := &geometryFetcher{stride: 100, last: 40, fileSize: 0 /* no geometry */, n: n}
	f := newGeometryFile(n, fetcher)
	if err := f.EnsureSegmentMap(); err != nil {
		t.Fatal(err)
	}
	fetcher.mu.Lock()
	distinct := len(fetcher.fetched)
	fetcher.mu.Unlock()
	if distinct < n-1 {
		t.Fatalf("control: expected the gap pass to fetch nearly all %d segments, fetched %d distinct", n, distinct)
	}
}

func TestSegmentMapSnapshotRoundtripsYencGeometry(t *testing.T) {
	const n = 12
	fetcher := &geometryFetcher{stride: 100, last: 40, fileSize: 11*100 + 40, n: n}
	f := newGeometryFile(n, fetcher)
	if err := f.EnsureSegmentMap(); err != nil {
		t.Fatal(err)
	}
	snap, ok := f.SegmentMapSnapshotJSON()
	if !ok {
		t.Fatal("no snapshot from detected map")
	}

	fresh := newGeometryFile(n, &geometryFetcher{stride: 100, last: 40, fileSize: 11*100 + 40, n: n})
	if !fresh.RestoreSegmentMapJSON(snap) {
		t.Fatalf("snapshot rejected on replay: %s", snap)
	}
	if fresh.Size() != f.Size() {
		t.Fatalf("restored total %d, want %d", fresh.Size(), f.Size())
	}
	for i := 0; i < n; i++ {
		as, ae, _ := f.SegmentOffsetRange(i)
		bs, be, _ := fresh.SegmentOffsetRange(i)
		if as != bs || ae != be {
			t.Fatalf("segment %d restored as [%d,%d), want [%d,%d)", i, bs, be, as, ae)
		}
	}
}

// varyingGeometryFetcher is a complete release whose middle article is longer
// than the estimator's inherited class size. Every article still has the same
// decoded length as the map predicts at the segment being read, so checking
// lengths alone cannot detect that the later article starts at a different
// logical offset.
type varyingGeometryFetcher struct {
	lengths []int64
	// omitMetadataAfterFirst makes later callbacks act like a cache path that
	// returns the body without repeating its yEnc headers.
	omitMetadataAfterFirst bool

	mu      sync.Mutex
	fetched map[int]int
	bodies  map[int][]byte
}

func (f *varyingGeometryFetcher) FetchSegment(_ context.Context, segment *nzb.Segment, _ []string) (pool.SegmentData, error) {
	idx := int(segment.Number) - 1
	if idx < 0 || idx >= len(f.lengths) {
		return pool.SegmentData{}, fmt.Errorf("unexpected segment number %d", segment.Number)
	}
	f.mu.Lock()
	if f.fetched == nil {
		f.fetched = make(map[int]int)
	}
	f.fetched[idx]++
	call := f.fetched[idx]
	if f.bodies == nil {
		f.bodies = make(map[int][]byte)
	}
	f.mu.Unlock()

	var offset int64
	for _, n := range f.lengths[:idx] {
		offset += n
	}
	size := f.lengths[idx]
	body := bytes.Repeat([]byte{byte('A' + idx)}, int(size))
	f.mu.Lock()
	if call == 1 {
		f.bodies[idx] = append([]byte(nil), body...)
	}
	f.mu.Unlock()
	data := pool.SegmentData{Body: body, Size: size}
	if !f.omitMetadataAfterFirst || call == 1 {
		data.YencFileSize = sumLengths(f.lengths)
		data.YencPartOffset = offset
	}
	return data, nil
}

func sumLengths(lengths []int64) int64 {
	var total int64
	for _, n := range lengths {
		total += n
	}
	return total
}

func (f *varyingGeometryFetcher) firstBody(index int) []byte {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]byte(nil), f.bodies[index]...)
}

func (f *varyingGeometryFetcher) fetchCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	total := 0
	for _, count := range f.fetched {
		total += count
	}
	return total
}

// A seek after an earlier varying-size article used to fetch a same-sized
// article at the wrong logical offset. The old length-only check accepted it;
// the observed yEnc part offset must make the read fail before bytes are
// copied as a successful frame.
func TestReadRejectsSameLengthArticleAtWrongYencOffset(t *testing.T) {
	const declaredBytes = int64(10)
	fetcher := &varyingGeometryFetcher{lengths: []int64{4, 7, 4}}
	estimator := NewSegmentSizeEstimator()
	estimator.Set(declaredBytes, 4)
	f := NewFile(context.Background(), testNZBFileWithSegments(declaredBytes, declaredBytes, declaredBytes), estimator, fetcher)

	if err := f.EnsureSegmentMap(); err != nil {
		t.Fatalf("EnsureSegmentMap returned error: %v", err)
	}
	if got := f.Size(); got != 12 {
		t.Fatalf("inherited map size = %d, want 12 for the reproduction", got)
	}

	buf := bytes.Repeat([]byte{0xcc}, 4)
	n, err := f.ReadAtCtx(context.Background(), buf, 8)
	if err == nil {
		t.Fatal("same-length article at a different yEnc offset was served successfully")
	}
	if n != 0 {
		t.Fatalf("geometry mismatch copied %d bytes before failing", n)
	}
	if !bytes.Equal(buf, bytes.Repeat([]byte{0xcc}, len(buf))) {
		t.Fatal("geometry mismatch changed the destination buffer")
	}
}

func TestEnsureSegmentMapRetainsProbeGeometryForLaterRead(t *testing.T) {
	fetcher := &varyingGeometryFetcher{
		lengths:                []int64{4, 7, 4, 4},
		omitMetadataAfterFirst: true,
	}
	ctx := WithSkipGapProbing(context.Background(), true)
	f := NewFile(ctx, testNZBFileWithSegments(10, 10, 10, 10), nil, fetcher)

	if err := f.EnsureSegmentMapCtx(ctx); err != nil {
		t.Fatalf("EnsureSegmentMap returned error: %v", err)
	}
	if got := f.Size(); got != 16 {
		t.Fatalf("fallback map size = %d, want 16 for the reproduction", got)
	}

	buf := bytes.Repeat([]byte{0xcc}, 4)
	n, err := f.ReadAtCtx(context.Background(), buf, 12)
	if err == nil {
		t.Fatal("last probe body was served at the wrong mapped offset")
	}
	if n != 0 {
		t.Fatalf("retained probe geometry copied %d bytes before failing", n)
	}
	if !bytes.Equal(buf, bytes.Repeat([]byte{0xcc}, len(buf))) {
		t.Fatal("retained probe geometry changed the destination buffer")
	}
}

func TestSegmentReaderRechecksPreDetectionProbeOnCachedRead(t *testing.T) {
	fetcher := &varyingGeometryFetcher{lengths: []int64{4, 7, 4, 4}}
	ctx := WithSkipGapProbing(context.Background(), true)
	f := NewFile(ctx, testNZBFileWithSegments(10, 10, 10, 10), nil, fetcher)
	if err := f.EnsureSegmentMapCtx(ctx); err != nil {
		t.Fatalf("EnsureSegmentMap returned error: %v", err)
	}

	reader := NewSegmentReader(ctx, f, 12)
	defer reader.Close()
	reader.mu.Lock()
	reader.currentSegIdx = 3
	reader.currentData = fetcher.firstBody(3)
	reader.segIdx = 3
	reader.segOff = 0
	reader.offset = 12
	reader.mu.Unlock()

	buf := bytes.Repeat([]byte{0xcc}, 4)
	n, err := reader.Read(buf)
	if err == nil {
		t.Fatal("cached pre-detection probe body was served at the wrong mapped offset")
	}
	if n != 0 || !bytes.Equal(buf, bytes.Repeat([]byte{0xcc}, len(buf))) {
		t.Fatalf("cached geometry mismatch returned n=%d or changed destination", n)
	}
}
