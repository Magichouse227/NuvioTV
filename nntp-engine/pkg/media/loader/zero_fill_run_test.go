package loader

import (
	"bytes"
	"io"
	"testing"

	"streamnzb/pkg/usenet/nntp"
)

// Missing articles are source-integrity failures regardless of whether they
// are isolated or adjacent. The old policy zero-filled up to ten of them and
// could feed invalid EBML bytes to a player.
func TestMissingArticlesNeverPadPlayableStream(t *testing.T) {
	const segments, segmentSize = 16, 1024
	f := strictDamagedFile(segments, segmentSize, newDamagedSegmentFetcher(segmentSize, 4, 5, 6, 7, 8))

	stream, err := f.OpenStreamCtx(playbackCtx())
	if err != nil {
		t.Fatalf("OpenStreamCtx: %v", err)
	}
	defer stream.Close()

	got, err := io.ReadAll(stream)
	if !nntp.IsArticleNotFound(err) {
		t.Fatalf("ReadAll error = %v, want missing-article error", err)
	}
	if want := 4 * segmentSize; len(got) != want {
		t.Fatalf("ReadAll returned %d bytes, want verified prefix %d", len(got), want)
	}
	for i := 0; i < 4; i++ {
		if want := segmentPayload(i, segmentSize); !bytes.Equal(got[i*segmentSize:(i+1)*segmentSize], want) {
			t.Fatalf("segment %d was not returned exactly", i)
		}
	}
}