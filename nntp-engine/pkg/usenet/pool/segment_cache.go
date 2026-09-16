package pool

import (
	"container/list"
	"sync"
	"sync/atomic"
)

type SegmentData struct {
	Body         []byte
	Size         int64
	ProviderHost string
	// FileName is the article's yEnc "=ybegin name=". An obfuscated release
	// carries no usable filename in its NZB subject, and this header is often
	// the only place the poster left the real one.
	FileName string
	// YencFileSize and YencPartOffset are the "=ybegin size=" and
	// "=ypart begin=" geometry of the article: the whole decoded file's size
	// and this part's exact offset within it. The loader builds exact segment
	// maps from them instead of probing and scaling. Zero YencFileSize means
	// the article carried no usable geometry.
	YencFileSize   int64
	YencPartOffset int64
}

// SegmentCacheBudget limits total segment cache memory across all sessions (0 = no limit).
type SegmentCacheBudget struct {
	maxBytes int64
	current  atomic.Int64
}

func (b *SegmentCacheBudget) CurrentBytes() int64 {
	if b == nil {
		return 0
	}
	return b.current.Load()
}

func (b *SegmentCacheBudget) MaxBytes() int64 {
	if b == nil {
		return 0
	}
	return b.maxBytes
}

// NewSegmentCacheBudget creates a budget of maxMB megabytes (0 = no limit).
func NewSegmentCacheBudget(maxMB int) *SegmentCacheBudget {
	if maxMB <= 0 {
		return nil
	}
	return &SegmentCacheBudget{maxBytes: int64(maxMB) * 1024 * 1024}
}

// Reserve adds n bytes to current usage if under the cap. Returns true if reserved.
func (b *SegmentCacheBudget) Reserve(n int64) bool {
	if b == nil || n <= 0 {
		return true
	}
	for {
		c := b.current.Load()
		if n <= b.maxBytes-c && b.current.CompareAndSwap(c, c+n) {
			return true
		}
		if n > b.maxBytes-c {
			return false
		}
	}
}

// Release subtracts n bytes from current usage.
func (b *SegmentCacheBudget) Release(n int64) {
	if b == nil || n <= 0 {
		return
	}
	b.current.Add(-n)
}

type SegmentCache interface {
	Get(messageID string) (SegmentData, bool)
	Set(messageID string, data SegmentData)
	// Purge drops this cache's entries and releases only its share of the budget.
	Purge()
}

type CacheStats struct {
	Entries       int
	Bytes         int64
	BudgetCurrent int64
	BudgetMax     int64
}

type segmentCacheStatser interface {
	Stats() CacheStats
}

// DefaultSegmentCacheCapacity is the fallback count-based cap when no budget is configured.
// 128 segments × ~750 KB = ~96 MB maximum, a safe default without a memory limit.
const DefaultSegmentCacheCapacity = 128

func NewMemorySegmentCache() SegmentCache {
	return NewMemorySegmentCacheWithBudget(nil)
}

func NewMemorySegmentCacheWithBudget(budget *SegmentCacheBudget) SegmentCache {
	return &memorySegmentCache{
		budget: budget,
		m:      make(map[string]*list.Element),
		lru:    list.New(),
	}
}

func NewMemorySegmentCacheWithCapacity(capacity int) SegmentCache {
	return &memorySegmentCache{
		capacity: capacity,
		m:        make(map[string]*list.Element),
		lru:      list.New(),
	}
}

type cacheEntry struct {
	key  string
	data SegmentData
}

type memorySegmentCache struct {
	mu       sync.Mutex
	budget   *SegmentCacheBudget
	capacity int // fallback/legacy limit if budget is nil
	m        map[string]*list.Element
	lru      *list.List
}

func (c *memorySegmentCache) Get(messageID string) (SegmentData, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if el, ok := c.m[messageID]; ok {
		c.lru.MoveToFront(el)
		return el.Value.(*cacheEntry).data, true
	}
	return SegmentData{}, false
}

func (c *memorySegmentCache) Set(messageID string, data SegmentData) {
	c.mu.Lock()
	defer c.mu.Unlock()

	size := int64(len(data.Body))
	// An uncacheable article must not flush useful seek/read-ahead data.
	if c.budget != nil && size > c.budget.MaxBytes() {
		return
	}

	if el, ok := c.m[messageID]; ok {
		oldSize := int64(len(el.Value.(*cacheEntry).data.Body))
		if c.budget != nil {
			// Keep the old entry accounted for until a replacement is secured.
			// Releasing it first let eviction release it twice and detach el.
			delta := size - oldSize
			for delta > 0 && !c.budget.Reserve(delta) {
				victim := c.lru.Back()
				if victim == el {
					victim = victim.Prev()
				}
				if victim == nil {
					return // another cache owns the remaining budget; retain the old value
				}
				c.removeLocked(victim)
			}
			if delta < 0 {
				c.budget.Release(-delta)
			}
		}
		c.lru.MoveToFront(el)
		el.Value.(*cacheEntry).data = data
		return
	}

	if c.budget != nil {
		reserved := c.budget.Reserve(size)
		if !reserved {
			// Evict until we can reserve
			for c.lru.Len() > 0 && !reserved {
				c.evictLocked()
				reserved = c.budget.Reserve(size)
			}
			if !reserved {
				return // too large or failed to reserve
			}
		}
	} else if c.capacity > 0 && c.lru.Len() >= c.capacity {
		c.evictLocked()
	} else if c.capacity <= 0 && c.lru.Len() >= DefaultSegmentCacheCapacity {
		c.evictLocked()
	}

	ent := &cacheEntry{key: messageID, data: data}
	el := c.lru.PushFront(ent)
	c.m[messageID] = el
}

func (c *memorySegmentCache) evictLocked() {
	c.removeLocked(c.lru.Back())
}

func (c *memorySegmentCache) removeLocked(el *list.Element) {
	if el == nil {
		return
	}
	ent := el.Value.(*cacheEntry)
	if c.budget != nil {
		c.budget.Release(int64(len(ent.data.Body)))
	}
	delete(c.m, ent.key)
	c.lru.Remove(el)
}

// Purge drops this cache's entries without releasing another session's budget.
func (c *memorySegmentCache) Purge() {
	c.mu.Lock()
	defer c.mu.Unlock()
	// Release all budget so future Reserves work without stale accounting.
	if c.budget != nil {
		for _, el := range c.m {
			ent := el.Value.(*cacheEntry)
			c.budget.Release(int64(len(ent.data.Body)))
		}
	}
	c.m = make(map[string]*list.Element)
	c.lru.Init()
}

func (c *memorySegmentCache) Stats() CacheStats {
	c.mu.Lock()
	defer c.mu.Unlock()

	stats := CacheStats{Entries: len(c.m)}
	if c.budget != nil {
		stats.BudgetCurrent = c.budget.CurrentBytes()
		stats.BudgetMax = c.budget.MaxBytes()
	}
	for _, el := range c.m {
		stats.Bytes += int64(len(el.Value.(*cacheEntry).data.Body))
	}
	return stats
}

func NoopSegmentCache() SegmentCache { return &noopSegmentCache{} }

type noopSegmentCache struct{}

func (n *noopSegmentCache) Get(messageID string) (SegmentData, bool) { return SegmentData{}, false }
func (n *noopSegmentCache) Set(messageID string, data SegmentData)   {}
func (n *noopSegmentCache) Purge()                                   {}
func (n *noopSegmentCache) Stats() CacheStats                        { return CacheStats{} }
