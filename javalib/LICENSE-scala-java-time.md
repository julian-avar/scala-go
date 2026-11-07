# License

javalib's `java.time` (`javalib/src/java/time`) is adapted from
[scala-java-time](https://github.com/cquiroz/scala-java-time) 2.9.0, the Scala port of
[ThreeTen backport](https://www.threeten.org/threetenbp/), which is provided under the
BSD 3-Clause License:

```
Copyright (c) 2007-present, Stephen Colebourne & Michael Nascimento Santos

All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

 * Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.

 * Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

 * Neither the name of JSR-310 nor the names of its contributors
   may be used to endorse or promote products derived from this software
   without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

The sources are scala-java-time's shared sources (`core/shared/src/main/scala` and
`scala-3`) with its own package renaming (`org.threeten.bp` to `java.time`). Besides mechanical
changes for javalib (Scala methods called with `()`, `Arrays.asList` on arrays, `Long` meaning
`scala.Long`), scala-go's changes are marked `scala-go:` in the code: the time-zone provider on
Go's tz database (`zone/ZoneRulesProvider.scala`; `zone/GoZoneRules.scala` is scala-go's own),
the system clock, English texts and patterns in place of locale data, thread-safe caches,
JDK 9+ methods, and the JDK's exception messages.
