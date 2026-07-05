// This module's path is deliberately namespaced under the upstream project's
// import path (github.com/teslamotors/vehicle-command/...). This is NOT a
// real subpackage of that project on disk -- it's a local fixture generator
// -- but Go's "internal/" visibility rule is enforced purely on the *import
// path string*, not on physical module boundaries. Naming this module
// github.com/teslamotors/vehicle-command/cmd/fixturegen lets us import the
// vendored internal/authentication and internal/dispatcher packages (where
// the real session/handshake/signing logic lives) without forking or
// modifying a single line of the vendored tree.
//
// Module resolution always goes through the replace directive below, which
// points at ./upstream (populated by fetch.sh from the commit pinned in
// pin.txt). The Go module proxy is never consulted for the vehicle-command
// module itself.
module github.com/teslamotors/vehicle-command/cmd/fixturegen

go 1.23

require (
	github.com/teslamotors/vehicle-command v0.0.0-00010101000000-000000000000
	google.golang.org/protobuf v1.34.2
)

require (
	github.com/cronokirby/saferith v0.33.0 // indirect
	github.com/golang-jwt/jwt/v5 v5.2.2 // indirect
)

replace github.com/teslamotors/vehicle-command => ./upstream
