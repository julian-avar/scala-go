//go:build unix

package rt

import "syscall"

func errnoKind(errno syscall.Errno) int32 {
	switch errno {
	case syscall.ECONNREFUSED:
		return netRefused
	case syscall.ECONNRESET:
		return netReset
	case syscall.EPIPE:
		return netBrokenPipe
	case syscall.EHOSTUNREACH:
		return netHostUnreachable
	case syscall.ENETUNREACH:
		return netNetworkUnreachable
	case syscall.EADDRINUSE:
		return netAddressInUse
	case syscall.EADDRNOTAVAIL:
		return netAddressNotAvailable
	case syscall.ETIMEDOUT:
		return netTimeout
	}
	return netOtherError
}

func socketBufferSize(raw syscall.RawConn, send bool) int32 {
	opt := syscall.SO_RCVBUF
	if send {
		opt = syscall.SO_SNDBUF
	}
	size := 0
	raw.Control(func(fd uintptr) {
		size, _ = syscall.GetsockoptInt(int(fd), syscall.SOL_SOCKET, opt)
	})
	return int32(size)
}
