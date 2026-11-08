package rt

import (
	"syscall"
	"unsafe"
)

// Winsock's error codes.
const (
	wsaeAddrInUse    syscall.Errno = 10048
	wsaeAddrNotAvail syscall.Errno = 10049
	wsaeNetUnreach   syscall.Errno = 10051
	wsaeConnAborted  syscall.Errno = 10053
	wsaeConnReset    syscall.Errno = 10054
	wsaeTimedOut     syscall.Errno = 10060
	wsaeConnRefused  syscall.Errno = 10061
	wsaeHostUnreach  syscall.Errno = 10065
)

func errnoKind(errno syscall.Errno) int32 {
	switch errno {
	case wsaeConnRefused:
		return netRefused
	case wsaeConnReset, wsaeConnAborted:
		return netReset
	case wsaeHostUnreach:
		return netHostUnreachable
	case wsaeNetUnreach:
		return netNetworkUnreachable
	case wsaeAddrInUse:
		return netAddressInUse
	case wsaeAddrNotAvail:
		return netAddressNotAvailable
	case wsaeTimedOut:
		return netTimeout
	}
	return netOtherError
}

func socketBufferSize(raw syscall.RawConn, send bool) int32 {
	opt := syscall.SO_RCVBUF
	if send {
		opt = syscall.SO_SNDBUF
	}
	size := int32(0)
	raw.Control(func(fd uintptr) {
		var v int32
		n := int32(4)
		if syscall.Getsockopt(syscall.Handle(fd), syscall.SOL_SOCKET, int32(opt), (*byte)(unsafe.Pointer(&v)), &n) == nil {
			size = v
		}
	})
	return size
}
