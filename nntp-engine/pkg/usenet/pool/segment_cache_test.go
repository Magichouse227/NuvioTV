package pool

import (
	"fmt"
	"sync"
	"testing"
)

func cacheTestData(size int) SegmentData {
	return SegmentData{Body: make([]byte, size), Size: int64(size)}
}

func TestSegmentCacheReplacementEvictsOnlyWhatItNeeds(t *testing.T) {
	budget := &SegmentCacheBudget{maxBytes: 10}
	cache := NewMemorySegmentCacheWithBudget(budget)
	cache.Set("replace", cacheTestData(4))
	cache.Set("oldest-other", cacheTestData(4))
	cache.Set("recent", cacheTestData(2))
	cache.Set("replace", cacheTestData(7))

	if got, ok := cache.Get("replace"); !ok || len(got.Body) != 7 {
		t.Fatal("replacement was detached or not updated")
	}
	if _, ok := cache.Get("oldest-other"); ok {
		t.Fatal("oldest other entry was not evicted")
	}
	if _, ok := cache.Get("recent"); !ok {
		t.Fatal("replacement unnecessarily evicted the recent seek cache")
	}
	if got := budget.CurrentBytes(); got != 9 {
		t.Fatalf("budget = %d, want 9", got)
	}
	cache.Purge()
	if got := budget.CurrentBytes(); got != 0 {
		t.Fatalf("budget after purge = %d, want 0", got)
	}
}

func TestSegmentCacheOversizedEntryPreservesExistingData(t *testing.T) {
	for _, key := range []string{"existing", "new"} {
		t.Run(key, func(t *testing.T) {
			budget := &SegmentCacheBudget{maxBytes: 10}
			cache := NewMemorySegmentCacheWithBudget(budget)
			cache.Set("existing", cacheTestData(4))
			cache.Set("sibling", cacheTestData(4))
			cache.Set(key, cacheTestData(11))
			for _, retained := range []string{"existing", "sibling"} {
				if got, ok := cache.Get(retained); !ok || len(got.Body) != 4 {
					t.Fatalf("%s was lost after an oversized insert", retained)
				}
			}
			if budget.CurrentBytes() != 8 {
				t.Fatalf("budget = %d, want 8", budget.CurrentBytes())
			}
			cache.Purge()
			if budget.CurrentBytes() != 0 {
				t.Fatal("purge double-released the old entry")
			}
		})
	}
}

func TestSegmentCacheFailedReplacementKeepsOtherSessionBudget(t *testing.T) {
	budget := &SegmentCacheBudget{maxBytes: 10}
	first := NewMemorySegmentCacheWithBudget(budget)
	second := NewMemorySegmentCacheWithBudget(budget)
	first.Set("first", cacheTestData(4))
	second.Set("second", cacheTestData(6))
	first.Set("first", cacheTestData(6))
	if got, ok := first.Get("first"); !ok || len(got.Body) != 4 {
		t.Fatal("failed replacement must retain its original entry")
	}
	if budget.CurrentBytes() != 10 {
		t.Fatalf("shared budget = %d, want 10", budget.CurrentBytes())
	}
	first.Purge()
	if budget.CurrentBytes() != 6 {
		t.Fatal("purging one session released the other session's budget")
	}
	second.Purge()
	if budget.CurrentBytes() != 0 {
		t.Fatal("all purged caches must release exactly their combined budget")
	}
}

func TestSegmentCacheShrinkingReplacementReleasesOnlyDifference(t *testing.T) {
	budget := &SegmentCacheBudget{maxBytes: 10}
	cache := NewMemorySegmentCacheWithBudget(budget)
	cache.Set("key", cacheTestData(8))
	cache.Set("key", cacheTestData(3))
	cache.Set("key", cacheTestData(3))
	if budget.CurrentBytes() != 3 {
		t.Fatalf("budget = %d, want 3", budget.CurrentBytes())
	}
	cache.Purge()
	if budget.CurrentBytes() != 0 {
		t.Fatal("shrinking replacement corrupted purge accounting")
	}
}

func TestSegmentCacheConcurrentSharedBudgetRemainsBounded(t *testing.T) {
	budget := &SegmentCacheBudget{maxBytes: 512}
	caches := []SegmentCache{
		NewMemorySegmentCacheWithBudget(budget),
		NewMemorySegmentCacheWithBudget(budget),
	}
	var workers sync.WaitGroup
	for worker := 0; worker < 8; worker++ {
		workers.Add(1)
		go func(worker int) {
			defer workers.Done()
			cache := caches[worker%len(caches)]
			for i := 0; i < 200; i++ {
				key := fmt.Sprintf("key-%d", i%13)
				cache.Set(key, cacheTestData(1+(i+worker)%32))
				cache.Get(key)
				if i%17 == 0 {
					cache.Purge()
				}
				if current := budget.CurrentBytes(); current < 0 || current > budget.MaxBytes() {
					t.Errorf("invalid shared usage: %d", current)
					return
				}
			}
		}(worker)
	}
	workers.Wait()
	var bytes int64
	for _, cache := range caches {
		bytes += cache.(segmentCacheStatser).Stats().Bytes
	}
	if budget.CurrentBytes() != bytes {
		t.Fatalf("accounted %d bytes, actual %d", budget.CurrentBytes(), bytes)
	}
	for _, cache := range caches {
		cache.Purge()
	}
	if budget.CurrentBytes() != 0 {
		t.Fatalf("final shared usage: %d", budget.CurrentBytes())
	}
}

func TestSegmentCacheBudgetRejectsOverflowingReservation(t *testing.T) {
	budget := &SegmentCacheBudget{maxBytes: 10}
	if !budget.Reserve(1) || budget.Reserve(1<<63-1) {
		t.Fatal("overflowing reservation must not bypass the byte limit")
	}
	if budget.CurrentBytes() != 1 {
		t.Fatal("failed reservation changed the counter")
	}
}

func TestSegmentCacheCountBasedLRURemainsBounded(t *testing.T) {
	cache := NewMemorySegmentCacheWithCapacity(2)
	cache.Set("a", cacheTestData(1))
	cache.Set("b", cacheTestData(1))
	cache.Get("a")
	cache.Set("c", cacheTestData(1))
	if _, ok := cache.Get("b"); ok {
		t.Fatal("count-based cache did not evict the least-recently-used entry")
	}
}
