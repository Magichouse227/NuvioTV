package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/base64"
	"encoding/xml"
	"fmt"
	"hash/crc32"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"
)

// These deterministic text payloads are used only when ffmpeg is unavailable.
// Normal test runs generate complete legal one-second media below, without
// adding binary fixtures to the command package.
const legalPlaybackMKVBase64 = "GkXfo6NChoEBQveBAULygQRC84EIQoKIbWF0cm9za2FCh4EEQoWBAhhTgGcBAAAAAAACnBFNm3TAv4Qgyo6MTbuLU6uEFUmpZlOsgaFNu4tTq4QWVK5rU6yB8U27jFOrhBJUw2dTrIIBgk27jFOrhBxTu2tTrIICbuwBAAAAAAAAUwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAFUmpZsu/hKhra8Aq17GDD0JATYCNTGF2ZjYwLjE2LjEwMFdBjUxhdmY2MC4xNi4xMDBzpJBED2FwkxYE+M2rCn7H02NkRImIQI9AAAAAAAAWVK5rQIu/hJWR8YKuAQAAAAAAAHzXgQFzxYinuiILeVaxtpyBACK1nIN1bmSIgQCDgQEj44OEHc1lAIaPVl9NUy9WRlcvRk9VUkND4ImwgRC6gRCagQJV7oEA7AEAAAAAAAACAABjoqgoAAAAEAAAABAAAAABABgARkZWMQADAAAAAAAAAAAAAAAAAAAAAAAAElTDZ0CAv4Rn8tX9c3OgY8CAZ8iaRaOHRU5DT0RFUkSHjUxhdmY2MC4xNi4xMDBzc9RjwItjxYinuiILeVaxtmfIn0Wjh0VOQ09ERVJEh5JMYXZjNjAuMzEuMTAyIGZmdjFnyKFFo4hEVVJBVElPTkSHkzAwOjAwOjAxLjAwMDAwMDAwMAAfQ7Z14b+E6ZZUHueBAKOqgQAAgPTHVzGp6Ol3EAF8cP2iCGu1am0A0r/+f///+AA9JXz//5+/7//4o6qBAfSA9MdXMano6XcQAXxw/aIIa7VqbQDSv/5////4AD0lfP//n7/v//gcU7trqb+Eyz8vILuPs4EAt4r3gQHxggII8IEJu5CzggH0t4r3gQHxggII8IE1"
const legalPlaybackMP4Base64 = "AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAAMxbW9vdgAAAGxtdmhkAAAAAAAAAAAAAAAAAAAD6AAAA+gAAQAAAQAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAlt0cmFrAAAAXHRraGQAAAADAAAAAAAAAAAAAAABAAAAAAAAA+gAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAABAAAAAQAAAAAAAkZWR0cwAAABxlbHN0AAAAAAAAAAEAAAPoAAAAAAABAAAAAAHTbWRpYQAAACBtZGhkAAAAAAAAAAAAAAAAAABAAAAAQABVxAAAAAAALWhkbHIAAAAAAAAAAHZpZGUAAAAAAAAAAAAAAABWaWRlb0hhbmRsZXIAAAABfm1pbmYAAAAUdm1oZAAAAAEAAAAAAAAAAAAAACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAAAAx1cmwgAAAAAQAAAT5zdGJsAAAAvnN0c2QAAAAAAAAAAQAAAK5hdmMxAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAABAAEABIAAAASAAAAAAAAAABFUxhdmM2MC4zMS4xMDIgbGlieDI2NAAAAAAAAAAAAAAAGP//AAAANGF2Y0MBZAAK/+EAF2dkAAqs2V7ARAAAAwAEAAADABA8SJZYAQAGaOvjyyLA/fj4AAAAABBwYXNwAAAAAQAAAAEAAAAUYnRydAAAAAAAABYQAAAWEAAAABhzdHRzAAAAAAAAAAEAAAACAAAgAAAAABRzdHNzAAAAAAAAAAEAAAABAAAAHHN0c2MAAAAAAAAAAQAAAAEAAAACAAAAAQAAABxzdHN6AAAAAAAAAAAAAAACAAACtgAAAAwAAAAUc3RjbwAAAAAAAAABAAADYQAAAGJ1ZHRhAAAAWm1ldGEAAAAAAAAAIWhkbHIAAAAAAAAAAG1kaXJhcHBsAAAAAAAAAAAAAAAALWlsbnN0AAAAlqXRvbwAAAB1kYXRhAAAAAQAAAABMYXZmNjAuMTYuMTAwAAAACGZyZWUAAALKbWRhdAAAAp8GBf//m9xF6b3m2Ui3lizYINkj7u94MjY0IC0gY29yZSAxNjQgLSBILjI2NC9NUEVHLTQgQVZDIGNvZGVjIC0gQ29weWxlZnQgMjAwMy0yMDI1IC0gaHR0cDovL3d3dy52aWRlb2xhbi5vcmcveDI2NC5odG1sIC0gb3B0aW9uczogY2FiYWM9MSByZWY9MyBkZWJsb2NrPTE6MDowIGFuYWx5c2U9MHgzOjB4MTEzIG1lPWhleCBzdWJtZT03IHBzeT0xIHBzeV9yZD0xLjAwOjAuMDAgbWl4ZWRfcmVmPTEgbWVfcmFuZ2U9MTYgY2hyb21hX21lPTEgdHJlbGxpcz0xIDh4OGRjdD0xIGNxbT0wIGRlYWR6b25lPTIxLDExIGZhc3RfcHNraXA9MSBjaHJvbWFfcXBfb2Zmc2V0PS0yIHRocmVhZHM9MSBsb29rYWhlYWRfdGhyZWFkcz0xIHNsaWNlZF90aHJlYWRzPTAgbnI9MCBkZWNpbWF0ZT0wIGludGVybGFjZWQ9MCBibHVyYXlfY29tcGF0PTAgY29uc3RyYWluZWRfaW50cmE9MCBiZnJhbWVzPTMgYl9weXJhbWlkPTIgYl9hZGFwdD0xIGJfYmlhcz0wIGRpcmVjdD0xIHdlaWdodGI9MSBvcGVuX2dvcD0wIHdlaWdodHA9MiBrZXlpbnQ9MjUwIGtleWludF9taW49MiBzY2VuZWN1dD00MCBpbnRyYV9yZWZyZXNoPTAgcmNfbG9va2FoZWFkPTQwIHJjPWNyZiBtYnRyZWU9MSBjcmY9MjMuMCBxplNpb24tZWRpdD0wIHFwbWluPTAgcXBtYXg9NjkgcXBzdGVwPTQgaXBfcmF0aW89MS40MCBhcT0xOjEuMDAAgAAAAA9liIQAFP/+98dPwKbq3CcAAAAIQZohbEEv/uA="

type playbackArticle struct {
	id   string
	wire []byte
}

// startPlaybackNNTPServer serves a complete, deterministic set of yEnc
// articles. It understands the commands used by startup STATs, provider
// validation, segment-map probes, and playback BODY requests.
func startPlaybackNNTPServer(t *testing.T, articles map[string][]byte) (string, func()) {
	t.Helper()

	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	done := make(chan struct{})
	var connections sync.Map

	go func() {
		defer close(done)
		for {
			connection, acceptErr := listener.Accept()
			if acceptErr != nil {
				return
			}
			connections.Store(connection, struct{}{})
			go func(connection net.Conn) {
				defer connections.Delete(connection)
				defer connection.Close()
				servePlaybackNNTPConnection(connection, articles)
			}(connection)
		}
	}()

	return listener.Addr().String(), func() {
		_ = listener.Close()
		connections.Range(func(key, _ any) bool {
			_ = key.(net.Conn).Close()
			return true
		})
		<-done
	}
}

func servePlaybackNNTPConnection(connection net.Conn, articles map[string][]byte) {
	reader := bufio.NewReader(connection)
	writer := bufio.NewWriter(connection)
	_, _ = writer.WriteString("200 playback regression NNTP ready\r\n")
	_ = writer.Flush()

	for {
		line, err := reader.ReadString('\n')
		if err != nil {
			return
		}
		fields := strings.Fields(line)
		if len(fields) == 0 {
			continue
		}
		command := strings.ToUpper(fields[0])
		switch command {
		case "AUTHINFO":
			if len(fields) > 1 && strings.EqualFold(fields[1], "USER") {
				_, _ = writer.WriteString("381 password required\r\n")
			} else {
				_, _ = writer.WriteString("281 authentication accepted\r\n")
			}
		case "DATE":
			_, _ = writer.WriteString("111 20240101000000\r\n")
		case "GROUP":
			_, _ = writer.WriteString("211 1 1 1 alt.binaries.test\r\n")
		case "STAT":
			id := playbackCommandID(fields)
			if _, ok := articles[id]; !ok {
				_, _ = writer.WriteString("430 no such article\r\n")
			} else {
				fmt.Fprintf(writer, "223 1 <%s>\r\n", id)
			}
		case "BODY":
			id := playbackCommandID(fields)
			article, ok := articles[id]
			if !ok {
				_, _ = writer.WriteString("430 no such article\r\n")
				_ = writer.Flush()
				continue
			}
			fmt.Fprintf(writer, "222 1 <%s>\r\n", id)
			_, _ = writer.Write(article)
		case "QUIT":
			_, _ = writer.WriteString("205 closing connection\r\n")
			_ = writer.Flush()
			return
		default:
			_, _ = writer.WriteString("500 unsupported command\r\n")
		}
		_ = writer.Flush()
	}
}

func playbackCommandID(fields []string) string {
	if len(fields) < 2 {
		return ""
	}
	return strings.Trim(fields[1], "<>")
}

func startPlaybackNZBServer(t *testing.T, nzbDocument string) string {
	t.Helper()
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, _ *http.Request) {
		writer.Header().Set("Content-Type", "application/x-nzb")
		_, _ = io.WriteString(writer, nzbDocument)
	}))
	t.Cleanup(server.Close)
	return server.URL
}

func createPlaybackSession(t *testing.T, subject, password string, payload []byte, partSize, corruptIndex int) (*sessionRegistry, *engineSession) {
	t.Helper()
	articles, segments := playbackArticles(t, payload, subject, partSize, corruptIndex)
	return createPlaybackSessionFromArticleSets(t, subject, password, segments, []map[string][]byte{articles})
}

func createPlaybackSessionFromArticleSets(t *testing.T, subject, password string, segments []playbackArticle, articleSets []map[string][]byte) (*sessionRegistry, *engineSession) {
	t.Helper()
	if len(articleSets) == 0 {
		t.Fatal("at least one playback NNTP server is required")
	}

	serverURLs := make([]string, 0, len(articleSets))
	for _, articles := range articleSets {
		nntpAddress, stopNNTP := startPlaybackNNTPServer(t, articles)
		t.Cleanup(stopNNTP)
		host, port, err := net.SplitHostPort(nntpAddress)
		if err != nil {
			t.Fatal(err)
		}
		serverURLs = append(serverURLs, fmt.Sprintf("nntp://user:password@%s:%s/2", host, port))
	}

	nzbServer := startPlaybackNZBServer(t, playbackNZBDocument(subject, password, segments))
	registry := newSessionRegistry(1, time.Minute)
	t.Cleanup(registry.closeAll)

	session, err := registry.create(createSessionRequest{
		NZBURL:  nzbServer,
		Servers: serverURLs,
	})
	if err != nil {
		t.Fatalf("registry.create() = %v", err)
	}
	return registry, session
}

func playbackNZBDocument(subject, password string, segments []playbackArticle) string {
	var document bytes.Buffer
	document.WriteString(`<?xml version="1.0" encoding="UTF-8"?><nzb xmlns="http://www.newzbin.com/DTD/2003/nzb">`)
	if password != "" {
		fmt.Fprintf(&document, `<head><meta type="password">%s</meta></head>`, password)
	}
	fmt.Fprintf(&document, `<file poster="playback-regression" date="1" subject="%s"><groups><group>alt.binaries.test</group></groups><segments>`, xmlEscape(subject))
	for index, segment := range segments {
		fmt.Fprintf(&document, `<segment bytes="%d" number="%d">%s</segment>`, len(segment.wire)-len(".\r\n"), index+1, xmlEscape(segment.id))
	}
	document.WriteString(`</segments></file></nzb>`)
	return document.String()
}

func xmlEscape(value string) string {
	var escaped bytes.Buffer
	_ = xml.EscapeText(&escaped, []byte(value))
	return escaped.String()
}

func playbackArticles(t *testing.T, payload []byte, filename string, partSize, corruptIndex int) (map[string][]byte, []playbackArticle) {
	t.Helper()
	if partSize <= 0 {
		t.Fatal("partSize must be positive")
	}
	partCount := (len(payload) + partSize - 1) / partSize
	articles := make(map[string][]byte, partCount)
	segments := make([]playbackArticle, 0, partCount)
	for index, offset := 0, 0; offset < len(payload); index, offset = index+1, offset+partSize {
		end := offset + partSize
		if end > len(payload) {
			end = len(payload)
		}
		id := fmt.Sprintf("playback-%03d@test", index)
		wire := encodePlaybackYEnc(payload[offset:end], int64(len(payload)), int64(offset), index+1, partCount, filename)
		if index == corruptIndex {
			wire = corruptPlaybackYEnc(t, wire)
		}
		articles[id] = wire
		segments = append(segments, playbackArticle{id: id, wire: wire})
	}
	return articles, segments
}

func encodePlaybackYEnc(data []byte, fileSize, offset int64, part, totalParts int, filename string) []byte {
	const lineLength = 128
	var encoded bytes.Buffer
	column := 0
	for _, value := range data {
		yencValue := byte((int(value) + 42) & 0xff)
		escaped := yencValue == 0x00 || yencValue == 0x0a || yencValue == 0x0d || yencValue == 0x3d
		width := 1
		if escaped {
			width = 2
		}
		if column+width > lineLength {
			encoded.WriteString("\r\n")
			column = 0
		}
		if escaped {
			encoded.WriteByte('=')
			encoded.WriteByte(byte((int(yencValue) + 64) & 0xff))
			column += 2
		} else {
			encoded.WriteByte(yencValue)
			column++
		}
	}

	var article bytes.Buffer
	fmt.Fprintf(&article, "=ybegin part=%d total=%d line=%d size=%d name=%s\r\n", part, totalParts, lineLength, fileSize, filename)
	fmt.Fprintf(&article, "=ypart begin=%d end=%d\r\n", offset+1, offset+int64(len(data)))
	article.Write(encoded.Bytes())
	fmt.Fprintf(&article, "\r\n=yend size=%d part=%d pcrc32=%08x\r\n", len(data), part, crc32.ChecksumIEEE(data))

	// A real NNTP server dot-stuffs each body line, and the article reader keeps
	// that stuffing in place for rapidyenc to remove.
	var wire bytes.Buffer
	for _, line := range bytes.SplitAfter(article.Bytes(), []byte("\r\n")) {
		if len(line) > 0 && line[0] == '.' {
			wire.WriteByte('.')
		}
		wire.Write(line)
	}
	wire.WriteString(".\r\n")
	return wire.Bytes()
}

func corruptPlaybackYEnc(t *testing.T, wire []byte) []byte {
	t.Helper()
	corrupt := append([]byte(nil), wire...)
	partHeader := bytes.Index(corrupt, []byte("=ypart "))
	if partHeader < 0 {
		t.Fatal("multipart yEnc article has no =ypart header")
	}
	bodyStartRel := bytes.Index(corrupt[partHeader:], []byte("\r\n"))
	if bodyStartRel < 0 {
		t.Fatal("multipart yEnc article has no body")
	}
	bodyStart := partHeader + bodyStartRel + len("\r\n")
	bodyEndRel := bytes.Index(corrupt[bodyStart:], []byte("\r\n=yend "))
	if bodyEndRel < 0 {
		t.Fatal("multipart yEnc article has no =yend footer")
	}
	bodyEnd := bodyStart + bodyEndRel
	for index := bodyStart; index < bodyEnd; index++ {
		if corrupt[index] >= 'A' && corrupt[index] <= 'z' && corrupt[index] != '=' {
			corrupt[index] ^= 1
			return corrupt
		}
	}
	t.Fatal("multipart yEnc article has no safe encoded byte to corrupt")
	return nil
}

func truncatePlaybackYEnc(t *testing.T, wire []byte) []byte {
	t.Helper()
	partHeader := bytes.Index(wire, []byte("=ypart "))
	if partHeader < 0 {
		t.Fatal("multipart yEnc article has no =ypart header")
	}
	bodyStartRel := bytes.Index(wire[partHeader:], []byte("\r\n"))
	if bodyStartRel < 0 {
		t.Fatal("multipart yEnc article has no body")
	}
	bodyStart := partHeader + bodyStartRel + len("\r\n")
	bodyEndRel := bytes.Index(wire[bodyStart:], []byte("\r\n=yend "))
	if bodyEndRel < 0 {
		t.Fatal("multipart yEnc article has no =yend footer")
	}
	bodyEnd := bodyStart + bodyEndRel
	if bodyEnd-bodyStart < 4 {
		t.Fatal("multipart yEnc article body is too short to truncate")
	}

	// Keep the yend trailer byte-for-byte intact while dropping encoded body
	// bytes. The decoder must reject the primary rather than accepting a
	// shortened frame whose stale size/CRC trailer happens to follow it.
	cut := bodyStart + (bodyEnd-bodyStart)/2
	truncated := make([]byte, 0, len(wire)-(bodyEnd-cut))
	truncated = append(truncated, wire[:cut]...)
	truncated = append(truncated, wire[bodyEnd:]...)
	return truncated
}

func requestPlaybackRange(t *testing.T, api http.Handler, sessionID string, payload []byte, start, end int64) *httptest.ResponseRecorder {
	t.Helper()
	request := httptest.NewRequest(http.MethodGet, "/v1/sessions/"+sessionID+"/stream", nil)
	request.Header.Set("Range", fmt.Sprintf("bytes=%d-%d", start, end))
	response := httptest.NewRecorder()
	api.ServeHTTP(response, request)
	if response.Code != http.StatusPartialContent {
		t.Fatalf("range %d-%d status = %d, body = %q", start, end, response.Code, response.Body.Bytes())
	}
	want := payload[start : end+1]
	if !bytes.Equal(response.Body.Bytes(), want) {
		t.Fatalf("range %d-%d differs at byte boundary: got %d bytes, want %d", start, end, response.Body.Len(), len(want))
	}
	return response
}

func readPlaybackFixture(t *testing.T, name string) []byte {
	t.Helper()
	_, source, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("runtime.Caller failed")
	}
	data, err := os.ReadFile(filepath.Join(filepath.Dir(source), "..", "..", "pkg", "media", "unpack", "testdata", name))
	if err != nil {
		t.Fatalf("read unpack fixture %q: %v", name, err)
	}
	return data
}

func validatePlaybackMP4WithFFprobe(t *testing.T, payload []byte) {
	t.Helper()
	ffprobe, err := exec.LookPath("ffprobe")
	if err != nil {
		t.Logf("ffprobe unavailable; skipping optional container validation")
		return
	}
	path := filepath.Join(t.TempDir(), "playback-regression.mp4")
	if err := os.WriteFile(path, payload, 0600); err != nil {
		t.Fatalf("write ffprobe fixture: %v", err)
	}
	command := exec.CommandContext(context.Background(), ffprobe, "-v", "error", "-show_entries", "format=format_name", "-of", "default=nw=1", path)
	output, err := command.CombinedOutput()
	if err != nil {
		t.Fatalf("ffprobe legal MP4: %v (%s)", err, output)
	}
	if !strings.Contains(string(output), "format_name=mov,mp4") {
		t.Fatalf("ffprobe reported unexpected format: %s", output)
	}
}

func legalPlaybackPayload(t *testing.T) ([]byte, bool) {
	t.Helper()
	ffmpeg, err := exec.LookPath("ffmpeg")
	if err == nil {
		path := filepath.Join(t.TempDir(), "playback-regression.mp4")
		command := exec.CommandContext(
			context.Background(),
			ffmpeg,
			"-hide_banner",
			"-loglevel", "error",
			"-y",
			"-f", "lavfi",
			"-i", "color=c=black:s=16x16:r=2:d=1",
			"-c:v", "libx264",
			"-pix_fmt", "yuv420p",
			"-movflags", "+faststart",
			path,
		)
		if output, commandErr := command.CombinedOutput(); commandErr == nil {
			if payload, readErr := os.ReadFile(path); readErr == nil && len(payload) > 0 {
				return payload, true
			}
		} else {
			t.Logf("ffmpeg legal fixture generation unavailable: %v", output)
		}
	}

	// The checked-in text fallback keeps the unit test runnable in small Go
	// environments without multimedia tools. It is still a complete,
	// byte-compared payload; ffprobe validation is only claimed for the
	// generated legal container above.
	encoded := legalPlaybackMP4Base64
	encoded = encoded[:len(encoded)-len(encoded)%4]
	payload, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		t.Fatalf("decode fallback playback fixture: %v", err)
	}
	return payload, false
}

func legalPlaybackMatroskaPayload(t *testing.T) []byte {
	t.Helper()
	ffmpeg, err := exec.LookPath("ffmpeg")
	if err == nil {
		path := filepath.Join(t.TempDir(), "playback-regression.mkv")
		command := exec.CommandContext(
			context.Background(),
			ffmpeg,
			"-hide_banner",
			"-loglevel", "error",
			"-y",
			"-f", "lavfi",
			"-i", "color=c=black:s=16x16:r=2:d=1",
			"-map_metadata", "-1",
			"-c:v", "ffv1",
			"-g", "1",
			"-f", "matroska",
			path,
		)
		if output, commandErr := command.CombinedOutput(); commandErr == nil {
			if payload, readErr := os.ReadFile(path); readErr == nil && len(payload) > 0 {
				return payload
			}
		} else {
			t.Logf("ffmpeg legal Matroska fixture generation unavailable: %v", output)
		}
	}

	payload, err := base64.StdEncoding.DecodeString(legalPlaybackMKVBase64)
	if err != nil {
		t.Fatalf("decode fallback Matroska fixture: %v", err)
	}
	return payload
}

func validatePlaybackMatroskaWithFFprobe(t *testing.T, payload []byte) {
	t.Helper()
	ffprobe, err := exec.LookPath("ffprobe")
	if err != nil {
		t.Logf("ffprobe unavailable; skipping optional Matroska validation")
		return
	}
	path := filepath.Join(t.TempDir(), "playback-regression.mkv")
	if err := os.WriteFile(path, payload, 0600); err != nil {
		t.Fatalf("write ffprobe Matroska fixture: %v", err)
	}
	command := exec.CommandContext(context.Background(), ffprobe, "-v", "error", "-show_entries", "format=format_name", "-of", "default=nw=1", path)
	output, err := command.CombinedOutput()
	if err != nil {
		t.Fatalf("ffprobe legal Matroska: %v (%s)", err, output)
	}
	if !strings.Contains(string(output), "format_name=matroska,webm") {
		t.Fatalf("ffprobe reported unexpected Matroska format: %s", output)
	}
}

func TestPlaybackRegressionPreservesLegalMediaAcrossRebuffersAndRanges(t *testing.T) {
	payload, legal := legalPlaybackPayload(t)
	if legal {
		validatePlaybackMP4WithFFprobe(t, payload)
	}

	registry, session := createPlaybackSession(t, "Playback.1080p.mp4", "", payload, 113, -1)
	api := newAPIServer(registry, "http://127.0.0.1:8191", "test-management-token-at-least-32-bytes")

	// The order deliberately models startup, a rebuffer at the tail, a seek
	// into a different article, and a return to the opening bytes.
	ranges := [][2]int64{
		{0, 96},
		{int64(len(payload)) - 128, int64(len(payload)) - 1},
		{107, 107 + 113 + 31},
		{113*4 + 7, 113*4 + 113 + 19},
		{13, 113 + 17},
	}
	for _, playbackRange := range ranges {
		requestPlaybackRange(t, api.routes(), session.id, payload, playbackRange[0], playbackRange[1])
	}
}

func TestPlaybackRegressionPreservesLegalMatroskaAcrossOutOfOrderRanges(t *testing.T) {
	payload := legalPlaybackMatroskaPayload(t)
	validatePlaybackMatroskaWithFFprobe(t, payload)

	const partSize = 83
	registry, session := createPlaybackSession(t, "Playback.1080p.mkv", "", payload, partSize, -1)
	api := newAPIServer(registry, "http://127.0.0.1:8191", "test-management-token-at-least-32-bytes")

	// These seeks deliberately cross yEnc article boundaries in a different
	// order than startup, including a tail rebuffer before returning to the
	// opening bytes.
	ranges := [][2]int64{
		{0, 53},
		{partSize - 10, 2*partSize + 29},
		{2*partSize + 7, 4*partSize + 23},
		{int64(len(payload)) - 121, int64(len(payload)) - 1},
		{11, partSize + 17},
	}
	for _, playbackRange := range ranges {
		requestPlaybackRange(t, api.routes(), session.id, payload, playbackRange[0], playbackRange[1])
	}
}

func TestPlaybackRegressionFallsBackAfterPrimaryTruncatedYEnc(t *testing.T) {
	payload, _ := legalPlaybackPayload(t)
	const (
		partSize    = 113
		corruptPart = 7
	)
	backupArticles, segments := playbackArticles(t, payload, "Fallback.1080p.mp4", partSize, -1)
	primaryArticles := make(map[string][]byte, len(backupArticles))
	for id, wire := range backupArticles {
		primaryArticles[id] = append([]byte(nil), wire...)
	}

	targetID := segments[corruptPart].id
	primaryArticles[targetID] = truncatePlaybackYEnc(t, primaryArticles[targetID])
	trailer := func(wire []byte) []byte {
		index := bytes.Index(wire, []byte("=yend "))
		if index < 0 {
			t.Fatal("yEnc article has no =yend trailer")
		}
		return wire[index:]
	}
	if !bytes.Equal(trailer(primaryArticles[targetID]), trailer(backupArticles[targetID])) {
		t.Fatal("primary fixture changed the yEnc trailer while truncating its body")
	}

	registry, session := createPlaybackSessionFromArticleSets(t, "Fallback.1080p.mp4", "", segments, []map[string][]byte{
		primaryArticles,
		backupArticles,
	})
	api := newAPIServer(registry, "http://127.0.0.1:8191", "test-management-token-at-least-32-bytes")

	// The request crosses into the malformed article, so a successful response
	// proves the backup supplied the same segment rather than an HTTP error or
	// a shifted following article.
	start := int64(corruptPart*partSize - 19)
	end := int64((corruptPart+1)*partSize + 19)
	requestPlaybackRange(t, api.routes(), session.id, payload, start, end)
}

func TestPlaybackRegressionStreamsHeaderEncryptedArchiveAcrossArticleBoundaries(t *testing.T) {
	archive := readPlaybackFixture(t, "hdrenc-stored.7z")
	payload := readPlaybackFixture(t, "hdrenc-stored-payload.bin")
	registry, session := createPlaybackSession(t, "obfuscated-release.7z", "test123", archive, 257, -1)
	api := newAPIServer(registry, "http://127.0.0.1:8191", "test-management-token-at-least-32-bytes")

	for _, playbackRange := range [][2]int64{
		{0, 127},
		{251, 251 + 511},
		{int64(len(payload)) - 192, int64(len(payload)) - 1},
		{64, 64 + 777},
	} {
		requestPlaybackRange(t, api.routes(), session.id, payload, playbackRange[0], playbackRange[1])
	}
}

func TestPlaybackRegressionNeverServesFollowingArticleAfterIntegrityFailure(t *testing.T) {
	payload, _ := legalPlaybackPayload(t)
	const (
		partSize    = 113
		corruptPart = 7
	)
	registry, session := createPlaybackSession(t, "Corrupt.1080p.mp4", "", payload, partSize, corruptPart)
	api := newAPIServer(registry, "http://127.0.0.1:8191", "test-management-token-at-least-32-bytes")

	start := int64(corruptPart*partSize - 19)
	end := int64((corruptPart+1)*partSize + 19)
	request := httptest.NewRequest(http.MethodGet, "/v1/sessions/"+session.id+"/stream", nil)
	request.Header.Set("Range", fmt.Sprintf("bytes=%d-%d", start, end))
	response := httptest.NewRecorder()
	api.routes().ServeHTTP(response, request)

	if response.Code != http.StatusPartialContent {
		t.Fatalf("integrity-failure range status = %d, body = %x", response.Code, response.Body.Bytes())
	}
	// The reader may return the bytes before the corrupt article together with
	// its error. It must never skip that failed article and splice in the next
	// valid one, which would silently shift the media stream.
	wantPrefix := payload[start : corruptPart*partSize]
	if !bytes.Equal(response.Body.Bytes(), wantPrefix) {
		t.Fatalf("integrity-failure body = %d bytes %x, want only the valid prefix of %d bytes", response.Body.Len(), response.Body.Bytes(), len(wantPrefix))
	}
	if response.Body.Len() >= int(end-start+1) {
		t.Fatalf("integrity failure was hidden by a complete range response (%d bytes)", response.Body.Len())
	}
}
