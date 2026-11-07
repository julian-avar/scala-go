package rt

// The native side of java.time (javalib/src/scalago/runtime/Time.scala): the clock, and time
// zones as Go *time.Locations. java.time's zone rules ask Go for the offset at an instant and for
// the period around it (time.Time.ZoneBounds), from which they find transitions, gaps and
// overlaps. So the tz data is Go's: the system's zoneinfo, or Go's embedded copy (time/tzdata),
// which the linker imports when zones are reachable.

import (
	"bytes"
	"io/fs"
	"math"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

// TimeNowNanos is the current time in nanoseconds since the epoch.
func TimeNowNanos() int64 { return time.Now().UnixNano() }

// zones caches loaded locations by region ID: time.LoadLocation reads and parses a file each time.
var zones sync.Map

// TimeZone returns the *time.Location of a region ID, or nil if Go knows no such zone.
func TimeZone(id *String) any {
	name := id.GoString()
	if loc, ok := zones.Load(name); ok {
		return loc
	}
	loc := loadZone(name)
	if loc == nil {
		return nil
	}
	actual, _ := zones.LoadOrStore(name, loc)
	return actual
}

func loadZone(name string) *time.Location {
	// "Local" is Go's name for its own idea of the local zone, not a region.
	if name == "" || name == "Local" {
		return nil
	}
	loc, err := time.LoadLocation(name)
	if err != nil {
		return nil
	}
	return loc
}

// TimeOffset is the offset from UTC, in seconds, of the zone at the instant epochSecond.
func TimeOffset(zone any, epochSecond int64) int32 {
	_, offset := time.Unix(epochSecond, 0).In(zone.(*time.Location)).Zone()
	return int32(offset)
}

// TimePeriod describes the period around epochSecond during which the zone keeps its offset:
// [offset, start, end, isDST], with math.MinInt64 and math.MaxInt64 for unbounded ends.
func TimePeriod(zone any, epochSecond int64) *Array[int64] {
	t := time.Unix(epochSecond, 0).In(zone.(*time.Location))
	_, offset := t.Zone()
	start, end := t.ZoneBounds()
	from, until := int64(math.MinInt64), int64(math.MaxInt64)
	if !start.IsZero() {
		from = start.Unix()
	}
	if !end.IsZero() {
		until = end.Unix()
	}
	dst := int64(0)
	if t.IsDST() {
		dst = 1
	}
	return ArrayOf(int64(offset), from, until, dst)
}

// TimeAbbreviation is the abbreviated name of the zone at epochSecond ("EST", "CEST", "+03").
func TimeAbbreviation(zone any, epochSecond int64) *String {
	name, _ := time.Unix(epochSecond, 0).In(zone.(*time.Location)).Zone()
	return Str(name)
}

// zoneinfoDirs are where Go looks for the system's tz database on Unix.
var zoneinfoDirs = []string{"/usr/share/zoneinfo/", "/usr/share/lib/zoneinfo/", "/usr/lib/locale/TZ/", "/etc/zoneinfo/"}

// TimeSystemZoneID is the ID of the system's time zone, found as the JDK does on Linux: the TZ
// environment variable, then /etc/timezone, then the target of the /etc/localtime link or the
// zoneinfo file with the same contents. "GMT" when none of them names a known zone.
func TimeSystemZoneID() *String { return Str(systemZoneID()) }

func systemZoneID() string {
	known := func(id string) bool { return loadZone(id) != nil }
	if tz := os.Getenv("TZ"); tz != "" {
		tz = strings.TrimPrefix(tz, ":")
		if strings.HasPrefix(tz, "/") {
			tz = zoneIDFromPath(tz)
		}
		if known(tz) {
			return tz
		}
		return "GMT"
	}
	if b, err := os.ReadFile("/etc/timezone"); err == nil {
		if id, _, _ := strings.Cut(string(b), "\n"); known(strings.TrimSpace(id)) {
			return strings.TrimSpace(id)
		}
	}
	if target, err := os.Readlink("/etc/localtime"); err == nil {
		if id := zoneIDFromPath(target); known(id) {
			return id
		}
	} else if content, err := os.ReadFile("/etc/localtime"); err == nil {
		if id := zoneWithContent(content); id != "" {
			return id
		}
	}
	return "GMT"
}

// zoneIDFromPath extracts "Europe/Paris" from "/usr/share/zoneinfo/Europe/Paris" (or from
// ".../zoneinfo/posix/Europe/Paris").
func zoneIDFromPath(path string) string {
	_, id, found := strings.Cut(path, "zoneinfo/")
	if !found {
		return ""
	}
	return strings.TrimPrefix(id, "posix/")
}

// zoneFiles calls f with the region ID and path of each tz data file of the system's database.
func zoneFiles(f func(id, path string) bool) {
	for _, dir := range zoneinfoDirs {
		if _, err := os.Stat(dir); err != nil {
			continue
		}
		_ = filepath.WalkDir(dir, func(path string, d fs.DirEntry, err error) error {
			if err != nil {
				return nil
			}
			id := strings.TrimPrefix(path, dir)
			if d.IsDir() {
				// Copies of the database with other leap-second conventions.
				if id == "posix" || id == "right" {
					return filepath.SkipDir
				}
				return nil
			}
			if id == "posixrules" || id == "localtime" || id == "Factory" || !isTZif(path) {
				return nil
			}
			if !f(id, path) {
				return filepath.SkipAll
			}
			return nil
		})
		return
	}
}

func isTZif(path string) bool {
	file, err := os.Open(path)
	if err != nil {
		return false
	}
	defer file.Close()
	magic := make([]byte, 4)
	n, _ := file.Read(magic)
	return n == 4 && string(magic) == "TZif"
}

func zoneWithContent(content []byte) string {
	found := ""
	zoneFiles(func(id, path string) bool {
		if b, err := os.ReadFile(path); err == nil && bytes.Equal(b, content) {
			found = id
			return false
		}
		return true
	})
	return found
}

// TimeZoneIDs lists the region IDs of the system's tz database, sorted.
func TimeZoneIDs() *Array[any] {
	var ids []string
	zoneFiles(func(id, _ string) bool {
		ids = append(ids, filepath.ToSlash(id))
		return true
	})
	sort.Strings(ids)
	out := make([]any, len(ids))
	for i, id := range ids {
		out[i] = Str(id)
	}
	return &Array[any]{Data: out}
}
