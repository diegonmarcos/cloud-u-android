// Copyright (c) 2025 RethinkDNS and its authors.
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

package settings

// msb to lsb: ipv6, ipv4, lwip(1) or netstack(0)
const (
	Ns4  = 0b010 // 2
	Ns46 = 0b110 // 6
	Ns6  = 0b100 // 4
)

// IP4, IP46, IP6 are string'd repr of Ns4, Ns46, Ns6
const (
	IP4  = "4"
	IP46 = "46"
	IP6  = "6"
)

// L3 returns the string'd repr of engine.
func L3(engine int) string {
	switch engine {
	case Ns46:
		return IP46
	case Ns6:
		return IP6
	default:
		return IP4
	}
}

// DELIBERATE BREAKAGE — task w262, to be reverted in the very next commit.
// This proves the 2026-09-10 decoupling: a firestack build that CANNOT COMPILE
// must fail ship-firestack-aar.yml and must NOT fail the SuperApp APK job.
// `intra/settings` is one of build.json::firestack.build.bind_packages, so
// gomobile bind compiles it and dies here.
func deliberatelyBrokenForDecouplingProof() { this is not valid Go }
