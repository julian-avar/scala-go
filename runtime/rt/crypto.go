package rt

import (
	"crypto/md5"
	"crypto/rand"
	"crypto/sha1"
	"crypto/sha256"
	"crypto/sha512"
	"encoding"
	"hash"
)

// The native side of java.util.UUID, java.security.MessageDigest and SecureRandom, and
// javax.crypto.Mac (javalib/src/scalago/runtime/Crypto.scala), on Go's crypto packages. A digest
// or MAC in progress is a *digest held by Scala as an Object; the Java API (algorithm names,
// exceptions, buffers) is written in Scala.

// SecureRandomBytes returns n bytes from the operating system's secure source.
func SecureRandomBytes(n int32) *Array[int8] {
	buf := make([]byte, n)
	if _, err := rand.Read(buf); err != nil {
		panic(err)
	}
	return ArrayOfSlice(Reinterpret[byte, int8](buf))
}

func MD5(data *Array[int8]) *Array[int8] {
	sum := md5.Sum(Reinterpret[int8, byte](data.Data))
	return ArrayOfSlice(Reinterpret[byte, int8](sum[:]))
}

// digestAlgorithms are the hash functions by their JDK standard names.
var digestAlgorithms = map[string]func() hash.Hash{
	"MD5":         md5.New,
	"SHA-1":       sha1.New,
	"SHA-224":     sha256.New224,
	"SHA-256":     sha256.New,
	"SHA-384":     sha512.New384,
	"SHA-512":     sha512.New,
	"SHA-512/224": sha512.New512_224,
	"SHA-512/256": sha512.New512_256,
}

// digest is a message digest, or an HMAC (RFC 2104) when opad is set: then h is the inner hash,
// which has already absorbed the key xor ipad. The HMAC is written here rather than with
// crypto/hmac so that it can be cloned (Mac.clone) like the hashes, whose states Go's hash
// packages can marshal.
type digest struct {
	h          hash.Hash
	newHash    func() hash.Hash
	ipad, opad []byte
}

// CryptoNewDigest starts a digest with one of digestAlgorithms, or returns nil.
func CryptoNewDigest(algorithm *String) any {
	newHash, ok := digestAlgorithms[algorithm.GoString()]
	if !ok {
		return nil
	}
	return &digest{h: newHash(), newHash: newHash}
}

// CryptoNewMac starts an HMAC with the digest algorithm and the key, or returns nil.
func CryptoNewMac(algorithm *String, key *Array[int8]) any {
	newHash, ok := digestAlgorithms[algorithm.GoString()]
	if !ok {
		return nil
	}
	h := newHash()
	k := Reinterpret[int8, byte](key.Data)
	if len(k) > h.BlockSize() {
		h.Write(k)
		k = h.Sum(nil)
		h.Reset()
	}
	d := &digest{h: h, newHash: newHash, ipad: make([]byte, h.BlockSize()), opad: make([]byte, h.BlockSize())}
	copy(d.ipad, k)
	copy(d.opad, k)
	for i := range d.ipad {
		d.ipad[i] ^= 0x36
		d.opad[i] ^= 0x5c
	}
	h.Write(d.ipad)
	return d
}

func CryptoUpdate(d any, b *Array[int8], off, n int32) {
	d.(*digest).h.Write(Reinterpret[int8, byte](b.Data[off : off+n]))
}

// CryptoDigest completes the digest (or MAC) and resets it.
func CryptoDigest(d any) *Array[int8] {
	s := d.(*digest)
	sum := s.h.Sum(nil)
	if s.opad != nil {
		outer := s.newHash()
		outer.Write(s.opad)
		outer.Write(sum)
		sum = outer.Sum(nil)
	}
	CryptoReset(d)
	return ArrayOfSlice(Reinterpret[byte, int8](sum))
}

func CryptoReset(d any) {
	s := d.(*digest)
	s.h.Reset()
	if s.ipad != nil {
		s.h.Write(s.ipad)
	}
}

func CryptoDigestLength(d any) int32 { return int32(d.(*digest).h.Size()) }

// CryptoClone copies a digest in progress.
func CryptoClone(d any) any {
	s := d.(*digest)
	state, err := s.h.(encoding.BinaryMarshaler).MarshalBinary()
	if err != nil {
		panic(err)
	}
	h := s.newHash()
	if err := h.(encoding.BinaryUnmarshaler).UnmarshalBinary(state); err != nil {
		panic(err)
	}
	c := *s
	c.h = h
	return &c
}
