package rt

import (
	"math"
	"testing"
	_ "time/tzdata"
)

func TestZoneIDFromPath(t *testing.T) {
	for path, want := range map[string]string{
		"/usr/share/zoneinfo/Europe/Prague":            "Europe/Prague",
		"../usr/share/zoneinfo/posix/America/New_York": "America/New_York",
		"/var/db/timezone/zoneinfo/UTC":                "UTC",
		"/etc/localtime":                               "",
	} {
		if got := zoneIDFromPath(path); got != want {
			t.Errorf("zoneIDFromPath(%q) = %q, want %q", path, got, want)
		}
	}
}

func TestTimePeriod(t *testing.T) {
	ny := TimeZone(Str("America/New_York"))
	if ny == nil {
		t.Fatal("America/New_York not found")
	}
	// 2024-03-10T07:00:00Z is the start of daylight saving time (02:00 EST).
	const start, end = 1710054000, 1730613600 // the period: until 2024-11-03T06:00:00Z
	p := TimePeriod(ny, start+3600).Data
	if p[0] != -4*3600 || p[1] != start || p[2] != end || p[3] != 1 {
		t.Errorf("period = %v", p)
	}
	if off := TimeOffset(ny, start-1); off != -5*3600 {
		t.Errorf("offset before the transition = %d", off)
	}
	utc := TimePeriod(TimeZone(Str("UTC")), 0).Data
	if utc[0] != 0 || utc[1] != math.MinInt64 || utc[2] != math.MaxInt64 {
		t.Errorf("UTC period = %v", utc)
	}
	if TimeZone(Str("Local")) != nil || TimeZone(Str("Mars/Olympus_Mons")) != nil {
		t.Error("unknown zones must be nil")
	}
	if got := TimeAbbreviation(ny, start).GoString(); got != "EDT" {
		t.Errorf("abbreviation = %q", got)
	}
}
