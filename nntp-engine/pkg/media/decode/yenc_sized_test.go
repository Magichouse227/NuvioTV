package decode

import (
	"bytes"
	"errors"
	"testing"

	"github.com/javi11/rapidyenc"
)

// A hint smaller than the article must grow transparently and still decode
// byte-identically; a generous hint must not leave the frame holding the
// over-allocation (trimExact clones past maxDecodeOverAllocation).
func TestDecodeToBytesSizedHints(t *testing.T) {
	payload, wire := benchCorpus(t)

	for _, tc := range []struct {
		name string
		hint int64
	}{
		{"no_hint", 0},
		{"exact_wire_size", int64(len(wire))},
		{"undersized", 4096},
		{"oversized", int64(len(wire)) * 4},
	} {
		t.Run(tc.name, func(t *testing.T) {
			frame, err := DecodeToBytesSized(bytes.NewReader(wire), tc.hint)
			if err != nil {
				t.Fatal(err)
			}
			if !bytes.Equal(frame.Data, payload) {
				t.Fatalf("decoded %d bytes, want %d, content mismatch", len(frame.Data), len(payload))
			}
			if over := cap(frame.Data) - len(frame.Data); over > maxDecodeOverAllocation {
				t.Fatalf("frame keeps %d bytes of spare capacity, budget-visible max is %d", over, maxDecodeOverAllocation)
			}
		})
	}
}

// The decoded frame must carry the article's declared geometry — the loader
// builds exact segment maps from it.
func TestDecodeFrameCarriesYencGeometry(t *testing.T) {
	payload, wire := benchCorpus(t)

	frame, err := DecodeToBytes(bytes.NewReader(wire))
	if err != nil {
		t.Fatal(err)
	}
	if frame.FileSize != int64(len(payload))*2 {
		t.Fatalf("FileSize = %d, want %d (the =ybegin size)", frame.FileSize, len(payload)*2)
	}
	if frame.PartOffset != 0 {
		t.Fatalf("PartOffset = %d, want 0 for part 1", frame.PartOffset)
	}

	// A continuation part reports its own exact offset.
	wire2 := buildWireAt(t, payload, int64(len(payload)))
	frame2, err := DecodeToBytes(bytes.NewReader(wire2))
	if err != nil {
		t.Fatal(err)
	}
	if frame2.PartOffset != int64(len(payload)) {
		t.Fatalf("PartOffset = %d, want %d (the =ypart begin-1)", frame2.PartOffset, len(payload))
	}
	if frame2.FileSize != int64(len(payload))*2 {
		t.Fatalf("FileSize = %d, want %d", frame2.FileSize, len(payload)*2)
	}
}

// dropYencBodyByte removes one encoded data byte without changing either the
// declared size or the footer CRC. This is the shape that the decoder must not
// accept as a successful frame: rapidyenc reports the size mismatch before it
// reaches its CRC check.
func dropYencBodyByte(t *testing.T, wire []byte) []byte {
	t.Helper()
	part := bytes.Index(wire, []byte("=ypart "))
	if part < 0 {
		t.Fatal("test article has no =ypart header")
	}
	bodyStartRel := bytes.Index(wire[part:], []byte("\r\n"))
	if bodyStartRel < 0 {
		t.Fatal("test article has no body")
	}
	bodyStart := part + bodyStartRel + len("\r\n")
	bodyEndRel := bytes.Index(wire[bodyStart:], []byte("\r\n=yend "))
	if bodyEndRel < 0 {
		t.Fatal("test article has no =yend footer")
	}
	bodyEnd := bodyStart + bodyEndRel
	for i := bodyStart; i < bodyEnd; i++ {
		if wire[i] != '\r' && wire[i] != '\n' {
			return append(append([]byte(nil), wire[:i]...), wire[i+1:]...)
		}
	}
	t.Fatal("test article has no encoded body byte")
	return nil
}

// alterYencBodyByte changes one encoded byte in place, preserving the encoded
// size and the original footer CRC.
func alterYencBodyByte(t *testing.T, wire []byte) []byte {
	t.Helper()
	altered := append([]byte(nil), wire...)
	part := bytes.Index(altered, []byte("=ypart "))
	bodyStartRel := bytes.Index(altered[part:], []byte("\r\n"))
	bodyStart := part + bodyStartRel + len("\r\n")
	bodyEndRel := bytes.Index(altered[bodyStart:], []byte("\r\n=yend "))
	bodyEnd := bodyStart + bodyEndRel
	for i := bodyStart; i < bodyEnd; i++ {
		if altered[i] != '\r' && altered[i] != '\n' {
			altered[i]++
			return altered
		}
	}
	t.Fatal("test article has no encoded body byte")
	return nil
}

func yencFooter(t *testing.T, wire []byte) []byte {
	t.Helper()
	startRel := bytes.Index(wire, []byte("\r\n=yend "))
	if startRel < 0 {
		t.Fatal("test article has no =yend footer")
	}
	start := startRel + len("\r\n")
	endRel := bytes.Index(wire[start:], []byte("\r\n"))
	if endRel < 0 {
		t.Fatal("test article has an unterminated =yend footer")
	}
	return append([]byte(nil), wire[start:start+endRel]...)
}

func TestDecodeRejectsTruncatedPayloadWithOriginalTrailer(t *testing.T) {
	data := bytes.Repeat([]byte{1}, 128)
	wire := stuffAndTerminate(manualYencArticle(data, 128), false)
	truncated := dropYencBodyByte(t, wire)

	if got, want := yencFooter(t, truncated), yencFooter(t, wire); !bytes.Equal(got, want) {
		t.Fatalf("truncated fixture footer = %q, want original %q", got, want)
	}
	frame, err := DecodeToBytesSized(bytes.NewReader(truncated), int64(len(wire)))
	if frame != nil {
		t.Fatal("truncated payload returned a frame")
	}
	if !errors.Is(err, rapidyenc.ErrDataCorruption) {
		t.Fatalf("truncated payload error = %v, want rapidyenc data corruption", err)
	}
}

func TestDecodeRejectsSameSizePayloadWithOriginalCRC(t *testing.T) {
	data := bytes.Repeat([]byte{1}, 128)
	wire := stuffAndTerminate(manualYencArticle(data, 128), false)
	altered := alterYencBodyByte(t, wire)

	if len(altered) != len(wire) {
		t.Fatalf("altered fixture length = %d, want %d", len(altered), len(wire))
	}
	if got, want := yencFooter(t, altered), yencFooter(t, wire); !bytes.Equal(got, want) {
		t.Fatalf("altered fixture footer = %q, want original %q", got, want)
	}
	frame, err := DecodeToBytesSized(bytes.NewReader(altered), int64(len(wire)))
	if frame != nil {
		t.Fatal("CRC-corrupt payload returned a frame")
	}
	if !errors.Is(err, rapidyenc.ErrCrcMismatch) {
		t.Fatalf("same-size corruption error = %v, want rapidyenc CRC mismatch", err)
	}
}
