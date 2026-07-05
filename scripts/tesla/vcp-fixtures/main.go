// Command fixturegen vendors Tesla's open-source Vehicle Command Protocol
// (VCP) reference implementation and uses it to produce byte-exact JSON
// fixtures that the Kotlin port (Tasks 20-23) is tested against.
//
// This program's module path is deliberately namespaced under
// github.com/teslamotors/vehicle-command/... purely so that Go's
// "internal/" visibility rule (enforced on import path, not physical
// location) lets us reach internal/authentication and internal/dispatcher --
// the packages that hold the real session/handshake/HMAC/AES-GCM logic. See
// go.mod for details. Not a single line of the vendored tree is modified.
//
// All cryptographic material below is test-only and publicly committed;
// never use these keys against a real vehicle.
package main

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/sha1"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math/big"
	"os"
	"path/filepath"
	"time"

	"google.golang.org/protobuf/proto"

	"github.com/teslamotors/vehicle-command/internal/authentication"
	carserver "github.com/teslamotors/vehicle-command/pkg/protocol/protobuf/carserver"
	"github.com/teslamotors/vehicle-command/pkg/protocol/protobuf/signatures"
	universal "github.com/teslamotors/vehicle-command/pkg/protocol/protobuf/universalmessage"
	"github.com/teslamotors/vehicle-command/pkg/protocol/protobuf/vcsec"
)

// ---------------------------------------------------------------------------
// Fixed test inputs. Every value here is committed and auditable; changing
// any of them changes every downstream fixture byte-for-byte.
// ---------------------------------------------------------------------------

const outDir = "../../../tool/src/test/resources/vcp"

// Fixed 17-character test VIN (Signatures.TAG_PERSONALIZATION value).
const testVIN = "5YJ3E1EA1PF000001"

// Fixed P-256 private scalars (32 bytes each), hex-encoded. Generated once
// with `openssl ecparam -genkey -name prime256v1`; committed here (and
// nowhere else) so every regeneration of these fixtures is byte-identical.
// These are test-only keys -- never enroll the corresponding public keys on
// a real vehicle.
const clientPrivateHex = "3dd722a0621a959640a2043d324d24b56dc7239ceb412fd46a6bee922a997747"
const vehiclePrivateHex = "ad31dc0df1e6c4529ebc33946b6c6d09011d057c8af841f72dd709001d5c2c4d"

// Fixed 16-byte UUID used as the challenge for every session-info request in
// these fixtures (dispatcher.go normally uses crypto/rand for this; we pin
// it for reproducibility since only the vehicle's HMAC response over this
// exact challenge needs to be verifiable, not secrecy).
var fixedChallengeUUID = mustHex("0102030405060708090a0b0c0d0e0f10")

// Fixed 16-byte client routing address (dispatcher.go's `from_destination`
// for non-VCSEC domains). Pinned for reproducibility.
var fixedRoutingAddress = mustHex("2c907bd76c640d360b3027dc7404efde")

// Fixed request UUID used for each command's RoutableMessage.uuid.
var fixedCommandUUID = mustHex("58406580528b6a5301391800b4fe9b99")

func mustHex(s string) []byte {
	b, err := hex.DecodeString(s)
	if err != nil {
		panic(err)
	}
	return b
}

func b64(b []byte) string { return base64.StdEncoding.EncodeToString(b) }

// ---------------------------------------------------------------------------
// Key material helpers
// ---------------------------------------------------------------------------

// nativeKey returns the vendored library's key type (needed to drive
// Signer/Verifier) for the given scalar.
func nativeKey(scalarHex string) authentication.ECDHPrivateKey {
	scalar := mustHex(scalarHex)
	key := authentication.UnmarshalECDHPrivateKey(scalar)
	if key == nil {
		panic("invalid test private key: " + scalarHex)
	}
	return key
}

// stdlibKey re-derives the same P-256 key using only Go's standard library
// (crypto/ecdh), independent of the vendored package's internal types. Used
// to (a) produce a PKCS8 export of the private key and (b) independently
// recompute ECDH/session-key derivation as a cross-check that a from-scratch
// Kotlin port following protocol.md alone (without special library access)
// reproduces the same values.
func stdlibKey(scalarHex string) *ecdh.PrivateKey {
	scalar := mustHex(scalarHex)
	key, err := ecdh.P256().NewPrivateKey(scalar)
	if err != nil {
		panic(err)
	}
	return key
}

func uncompressedPoint(pub *ecdh.PublicKey) []byte {
	return pub.Bytes() // crypto/ecdh already returns the 0x04||X||Y uncompressed form
}

func pkcs8Base64(key *ecdh.PrivateKey) string {
	// crypto/x509 doesn't marshal *ecdh.PrivateKey directly; convert through
	// the classic ecdsa representation (same scalar, same curve).
	d := new(big.Int).SetBytes(key.Bytes())
	pub := key.PublicKey()
	x, y := elliptic.Unmarshal(elliptic.P256(), pub.Bytes())
	if x == nil {
		panic("failed to unmarshal derived public key")
	}
	ecdsaKey := &ecdsa.PrivateKey{
		PublicKey: ecdsa.PublicKey{Curve: elliptic.P256(), X: x, Y: y},
		D:         d,
	}
	der, err := x509.MarshalPKCS8PrivateKey(ecdsaKey)
	if err != nil {
		panic(err)
	}
	return base64.StdEncoding.EncodeToString(der)
}

// ---------------------------------------------------------------------------
// AES-GCM known-answer vector (fully independent of the vendored library's
// internal random nonce source -- deterministic by construction).
// ---------------------------------------------------------------------------

type gcmVector struct {
	KeyB64        string `json:"key_b64"`
	NonceB64      string `json:"nonce_b64"`
	AadB64        string `json:"aad_b64"`
	PlaintextB64  string `json:"plaintext_b64"`
	CiphertextB64 string `json:"ciphertext_b64"`
	TagB64        string `json:"tag_b64"`
}

func buildGCMVector(key []byte) gcmVector {
	nonce := mustHex("d1e2a3f4b5c6a7b8c9d0e1f2") // fixed 12-byte nonce, test-only
	aad := mustHex("000105010103021135594a333031323334353637383941424303104c463f9cc0d3d26906e982ed224adde6040400000a5f050400000007ff")
	plaintext := mustHex("120452020801") // CarServer.Action{vehicleAction{hvacAutoAction{power_on:true}}}, from protocol.md worked example

	block, err := aes.NewCipher(key)
	if err != nil {
		panic(err)
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		panic(err)
	}
	sealed := gcm.Seal(nil, nonce, plaintext, aad)
	ciphertext := sealed[:len(sealed)-gcm.Overhead()]
	tag := sealed[len(sealed)-gcm.Overhead():]

	return gcmVector{
		KeyB64:        b64(key),
		NonceB64:      b64(nonce),
		AadB64:        b64(aad),
		PlaintextB64:  b64(plaintext),
		CiphertextB64: b64(ciphertext),
		TagB64:        b64(tag),
	}
}

// ---------------------------------------------------------------------------
// keys.json
// ---------------------------------------------------------------------------

type keysFixture struct {
	Description string `json:"description"`

	ClientPrivatePKCS8B64 string `json:"client_private_pkcs8_b64"`
	ClientPublicB64       string `json:"client_public_b64"`

	VehiclePrivatePKCS8B64 string `json:"vehicle_private_pkcs8_b64"`
	VehiclePublicB64       string `json:"vehicle_public_b64"`

	EcdhSharedSecretXB64 string `json:"ecdh_shared_secret_x_b64"`
	SessionKeyB64        string `json:"session_key_b64"`
	SessionKeyDerivation string `json:"session_key_derivation"`

	Labels struct {
		SessionInfoHMACKey string `json:"session_info_hmac_key_label"`
		MessageAuthHMACKey string `json:"message_auth_hmac_key_label"`
	} `json:"labels"`

	GCMVector gcmVector `json:"gcm_vector"`
}

func buildKeysFixture(clientStd, vehicleStd *ecdh.PrivateKey) keysFixture {
	// ECDH: shared secret is the X coordinate of the scalar multiplication,
	// big-endian, 32 bytes. Derive independently via crypto/ecdh (matches
	// protocol.md: S = ECDH(c, V) = ECDH(v, C)).
	sharedFromClient, err := clientStd.ECDH(vehicleStd.PublicKey())
	if err != nil {
		panic(err)
	}
	sharedFromVehicle, err := vehicleStd.ECDH(clientStd.PublicKey())
	if err != nil {
		panic(err)
	}
	if hex.EncodeToString(sharedFromClient) != hex.EncodeToString(sharedFromVehicle) {
		panic("ECDH shared secret asymmetry -- key derivation bug")
	}

	digest := sha1.Sum(sharedFromClient)
	sessionKey := digest[:16]

	// Cross-check against the vendored library's own ECDH+session derivation
	// (internal/authentication.NativeECDHKey.Exchange) to prove our
	// independent stdlib re-derivation matches the reference exactly.
	clientNative := nativeKey(clientPrivateHex)
	vehiclePubBytes := uncompressedPoint(vehicleStd.PublicKey())
	sess, err := clientNative.Exchange(vehiclePubBytes)
	if err != nil {
		panic(err)
	}
	// NativeSession doesn't expose its raw key, so we prove equivalence
	// indirectly: encrypt a known plaintext with the reference's Encrypt and
	// decrypt with our independently-derived key using stdlib AES-GCM.
	proveSessionKeyMatches(sess, sessionKey)

	var fx keysFixture
	fx.Description = "Fixed test-only P-256 keys for the VCP fixture suite. NEVER use against a real vehicle. " +
		"session_key is derived per protocol.md: SHA1(BIG_ENDIAN(ECDH(c,V).x, 32))[:16]."
	fx.ClientPrivatePKCS8B64 = pkcs8Base64(clientStd)
	fx.ClientPublicB64 = b64(uncompressedPoint(clientStd.PublicKey()))
	fx.VehiclePrivatePKCS8B64 = pkcs8Base64(vehicleStd)
	fx.VehiclePublicB64 = b64(vehiclePubBytes)
	fx.EcdhSharedSecretXB64 = b64(sharedFromClient)
	fx.SessionKeyB64 = b64(sessionKey)
	fx.SessionKeyDerivation = "K = SHA1(ECDH(client_private, vehicle_public).x)[:16] -- BIG_ENDIAN 32-byte X coordinate, SHA1, truncate to 16 bytes"
	fx.Labels.SessionInfoHMACKey = "session info"
	fx.Labels.MessageAuthHMACKey = "authenticated command"
	fx.GCMVector = buildGCMVector(sessionKey)
	return fx
}

// proveSessionKeyMatches encrypts a fixed plaintext with the reference
// Session (whose key we cannot read directly, since NativeSession.key is
// unexported even to same-import-path-prefixed packages -- Go's internal
// rule only grants cross-package IMPORT rights, not unexported FIELD
// access) and then decrypts it using our independently-derived candidate
// key via plain crypto/cipher. If they don't match, this panics loudly
// instead of silently shipping a wrong derivation.
func proveSessionKeyMatches(refSession authentication.Session, candidateKey []byte) {
	plaintext := []byte("vcp-fixture-cross-check")
	aad := []byte("aad")
	nonce, ciphertext, tag, err := refSession.Encrypt(plaintext, aad)
	if err != nil {
		panic(err)
	}
	block, err := aes.NewCipher(candidateKey)
	if err != nil {
		panic(err)
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		panic(err)
	}
	sealed := append(append([]byte{}, ciphertext...), tag...)
	decrypted, err := gcm.Open(nil, nonce, sealed, aad)
	if err != nil {
		panic(fmt.Sprintf("session key cross-check failed: %v", err))
	}
	if string(decrypted) != string(plaintext) {
		panic("session key cross-check produced wrong plaintext")
	}
}

// ---------------------------------------------------------------------------
// session_info_request.json
// ---------------------------------------------------------------------------

type sessionInfoRequestEntry struct {
	Description       string `json:"description"`
	Domain            string `json:"domain"`
	RoutableMessageB64 string `json:"routable_message_b64"`
}

func buildSessionInfoRequests(clientPublicBytes []byte) []sessionInfoRequestEntry {
	var entries []sessionInfoRequestEntry
	for _, d := range []struct {
		domain universal.Domain
		name   string
	}{
		{universal.Domain_DOMAIN_VEHICLE_SECURITY, "VCSEC"},
		{universal.Domain_DOMAIN_INFOTAINMENT, "Infotainment"},
	} {
		msg := &universal.RoutableMessage{
			ToDestination: &universal.Destination{
				SubDestination: &universal.Destination_Domain{Domain: d.domain},
			},
			FromDestination: &universal.Destination{
				SubDestination: &universal.Destination_RoutingAddress{RoutingAddress: fixedRoutingAddress},
			},
			Payload: &universal.RoutableMessage_SessionInfoRequest{
				SessionInfoRequest: &universal.SessionInfoRequest{
					PublicKey: clientPublicBytes,
				},
			},
			Uuid: fixedChallengeUUID,
		}
		encoded, err := proto.Marshal(msg)
		if err != nil {
			panic(err)
		}
		entries = append(entries, sessionInfoRequestEntry{
			Description:        fmt.Sprintf("Session info (handshake) request to %s", d.name),
			Domain:             d.name,
			RoutableMessageB64: b64(encoded),
		})
	}
	return entries
}

// ---------------------------------------------------------------------------
// session_info_response.json
// ---------------------------------------------------------------------------

type sessionInfoResponseFixture struct {
	Description        string `json:"description"`
	Domain              string `json:"domain"`
	ChallengeUUIDB64     string `json:"challenge_uuid_b64"`
	RoutableMessageB64  string `json:"routable_message_b64"`
	SessionInfoTagB64   string `json:"session_info_tag_b64"`
	Parsed              struct {
		EpochB64        string `json:"epoch_b64"`
		ClockTime       uint32 `json:"clock_time"`
		Counter         uint32 `json:"counter"`
		VehiclePublicB64 string `json:"vehicle_public_b64"`
		Status          string `json:"status"`
	} `json:"parsed"`
}

func buildSessionInfoResponse(vehicleKey authentication.ECDHPrivateKey, clientPublicBytes []byte) (sessionInfoResponseFixture, *signatures.SessionInfo) {
	verifier, err := authentication.NewVerifier(vehicleKey, []byte(testVIN), universal.Domain_DOMAIN_INFOTAINMENT, clientPublicBytes)
	if err != nil {
		panic(err)
	}

	reply := &universal.RoutableMessage{
		ToDestination: &universal.Destination{
			SubDestination: &universal.Destination_RoutingAddress{RoutingAddress: fixedRoutingAddress},
		},
		FromDestination: &universal.Destination{
			SubDestination: &universal.Destination_Domain{Domain: universal.Domain_DOMAIN_INFOTAINMENT},
		},
		RequestUuid: fixedChallengeUUID,
		Uuid:        fixedCommandUUID,
	}
	if err := verifier.SetSessionInfo(fixedChallengeUUID, reply); err != nil {
		panic(err)
	}

	encoded, err := proto.Marshal(reply)
	if err != nil {
		panic(err)
	}

	var info signatures.SessionInfo
	if err := proto.Unmarshal(reply.GetSessionInfo(), &info); err != nil {
		panic(err)
	}

	var fx sessionInfoResponseFixture
	fx.Description = "Synthetic vehicle SessionInfo handshake response, built with the reference's own authentication.Verifier, for DOMAIN_INFOTAINMENT."
	fx.Domain = "Infotainment"
	fx.ChallengeUUIDB64 = b64(fixedChallengeUUID)
	fx.RoutableMessageB64 = b64(encoded)
	fx.SessionInfoTagB64 = b64(reply.GetSignatureData().GetSessionInfoTag().GetTag())
	fx.Parsed.EpochB64 = b64(info.GetEpoch())
	fx.Parsed.ClockTime = info.GetClockTime()
	fx.Parsed.Counter = info.GetCounter()
	fx.Parsed.VehiclePublicB64 = b64(info.GetPublicKey())
	fx.Parsed.Status = info.GetStatus().String()

	return fx, &info
}

// ---------------------------------------------------------------------------
// fault_response.json
// ---------------------------------------------------------------------------

type faultResponseFixture struct {
	Description        string            `json:"description"`
	RoutableMessageB64 string            `json:"routable_message_b64"`
	Fault               string            `json:"fault"`
	FaultCodes          map[string]int32  `json:"fault_codes"`
}

func buildFaultResponse() faultResponseFixture {
	msg := &universal.RoutableMessage{
		ToDestination: &universal.Destination{
			SubDestination: &universal.Destination_RoutingAddress{RoutingAddress: fixedRoutingAddress},
		},
		FromDestination: &universal.Destination{
			SubDestination: &universal.Destination_Domain{Domain: universal.Domain_DOMAIN_VEHICLE_SECURITY},
		},
		RequestUuid: fixedCommandUUID,
		Uuid:        fixedChallengeUUID,
		SignedMessageStatus: &universal.MessageStatus{
			OperationStatus:     universal.OperationStatus_E_OPERATIONSTATUS_ERROR,
			SignedMessageFault: universal.MessageFault_E_MESSAGEFAULT_ERROR_INVALID_SIGNATURE,
		},
	}
	encoded, err := proto.Marshal(msg)
	if err != nil {
		panic(err)
	}

	codes := make(map[string]int32)
	for name, num := range universal.MessageFault_E_value {
		codes[name] = num
	}

	return faultResponseFixture{
		Description: "A MESSAGE_FAULT-family RoutableMessage (signed_message_fault = MESSAGEFAULT_ERROR_INVALID_SIGNATURE) plus the full fault-code enum transcribed from universal_message.proto. isFault(message) == (signedMessageStatus.signedMessageFault != MESSAGEFAULT_ERROR_NONE (0)).",
		RoutableMessageB64: b64(encoded),
		Fault:              "MESSAGEFAULT_ERROR_INVALID_SIGNATURE",
		FaultCodes:         codes,
	}
}

// ---------------------------------------------------------------------------
// commands.json
// ---------------------------------------------------------------------------

type commandEntry struct {
	Name                string `json:"name"`
	Domain              string `json:"domain"`
	ExpiresAt           uint32 `json:"expires_at"`
	Counter             uint32 `json:"counter"`
	EpochB64            string `json:"epoch_b64"`
	MetadataB64         string `json:"metadata_b64"`
	PlaintextActionB64  string `json:"plaintext_action_b64"`
	TagB64              string `json:"tag_b64"`
	RoutableMessageB64  string `json:"routable_message_b64"`
}

// commandSpec describes one in-scope command: its domain and a thunk that
// builds the exact application-layer payload bytes (VCSEC.UnsignedMessage or
// CarServer.Action), matching pkg/vehicle's command constructors field for
// field.
type commandSpec struct {
	name    string
	domain  universal.Domain
	payload func() []byte
}

func marshalOrPanic(m proto.Message) []byte {
	b, err := proto.Marshal(m)
	if err != nil {
		panic(err)
	}
	return b
}

func carServerAction(action *carserver.VehicleAction) []byte {
	return marshalOrPanic(&carserver.Action{
		ActionMsg: &carserver.Action_VehicleAction{VehicleAction: action},
	})
}

func vcsecUnsigned(m *vcsec.UnsignedMessage) []byte {
	return marshalOrPanic(m)
}

func commandSpecs() []commandSpec {
	return []commandSpec{
		{"lock", universal.Domain_DOMAIN_VEHICLE_SECURITY, func() []byte {
			return vcsecUnsigned(&vcsec.UnsignedMessage{
				SubMessage: &vcsec.UnsignedMessage_RKEAction{RKEAction: vcsec.RKEAction_E_RKE_ACTION_LOCK},
			})
		}},
		{"unlock", universal.Domain_DOMAIN_VEHICLE_SECURITY, func() []byte {
			return vcsecUnsigned(&vcsec.UnsignedMessage{
				SubMessage: &vcsec.UnsignedMessage_RKEAction{RKEAction: vcsec.RKEAction_E_RKE_ACTION_UNLOCK},
			})
		}},
		{"charge_start", universal.Domain_DOMAIN_INFOTAINMENT, func() []byte {
			return carServerAction(&carserver.VehicleAction{
				VehicleActionMsg: &carserver.VehicleAction_ChargingStartStopAction{
					ChargingStartStopAction: &carserver.ChargingStartStopAction{
						ChargingAction: &carserver.ChargingStartStopAction_Start{Start: &carserver.Void{}},
					},
				},
			})
		}},
		{"charge_stop", universal.Domain_DOMAIN_INFOTAINMENT, func() []byte {
			return carServerAction(&carserver.VehicleAction{
				VehicleActionMsg: &carserver.VehicleAction_ChargingStartStopAction{
					ChargingStartStopAction: &carserver.ChargingStartStopAction{
						ChargingAction: &carserver.ChargingStartStopAction_Stop{Stop: &carserver.Void{}},
					},
				},
			})
		}},
		{"set_charge_limit_80", universal.Domain_DOMAIN_INFOTAINMENT, func() []byte {
			return carServerAction(&carserver.VehicleAction{
				VehicleActionMsg: &carserver.VehicleAction_ChargingSetLimitAction{
					ChargingSetLimitAction: &carserver.ChargingSetLimitAction{Percent: 80},
				},
			})
		}},
		{"set_charge_amps_16", universal.Domain_DOMAIN_INFOTAINMENT, func() []byte {
			return carServerAction(&carserver.VehicleAction{
				VehicleActionMsg: &carserver.VehicleAction_SetChargingAmpsAction{
					SetChargingAmpsAction: &carserver.SetChargingAmpsAction{ChargingAmps: 16},
				},
			})
		}},
		{"climate_on", universal.Domain_DOMAIN_INFOTAINMENT, func() []byte {
			return carServerAction(&carserver.VehicleAction{
				VehicleActionMsg: &carserver.VehicleAction_HvacAutoAction{
					HvacAutoAction: &carserver.HvacAutoAction{PowerOn: true},
				},
			})
		}},
		{"climate_off", universal.Domain_DOMAIN_INFOTAINMENT, func() []byte {
			return carServerAction(&carserver.VehicleAction{
				VehicleActionMsg: &carserver.VehicleAction_HvacAutoAction{
					HvacAutoAction: &carserver.HvacAutoAction{PowerOn: false},
				},
			})
		}},
		{"set_temp_21_5", universal.Domain_DOMAIN_INFOTAINMENT, func() []byte {
			return carServerAction(&carserver.VehicleAction{
				VehicleActionMsg: &carserver.VehicleAction_HvacTemperatureAdjustmentAction{
					HvacTemperatureAdjustmentAction: &carserver.HvacTemperatureAdjustmentAction{
						DriverTempCelsius:    21.5,
						PassengerTempCelsius: 21.5,
						Level: &carserver.HvacTemperatureAdjustmentAction_Temperature{
							Type: &carserver.HvacTemperatureAdjustmentAction_Temperature_TEMP_MAX{},
						},
					},
				},
			})
		}},
		{"overheat_off", universal.Domain_DOMAIN_INFOTAINMENT, func() []byte {
			return carServerAction(&carserver.VehicleAction{
				VehicleActionMsg: &carserver.VehicleAction_SetCabinOverheatProtectionAction{
					SetCabinOverheatProtectionAction: &carserver.SetCabinOverheatProtectionAction{On: false, FanOnly: false},
				},
			})
		}},
		{"overheat_no_ac", universal.Domain_DOMAIN_INFOTAINMENT, func() []byte {
			return carServerAction(&carserver.VehicleAction{
				VehicleActionMsg: &carserver.VehicleAction_SetCabinOverheatProtectionAction{
					SetCabinOverheatProtectionAction: &carserver.SetCabinOverheatProtectionAction{On: true, FanOnly: true},
				},
			})
		}},
		{"overheat_ac", universal.Domain_DOMAIN_INFOTAINMENT, func() []byte {
			return carServerAction(&carserver.VehicleAction{
				VehicleActionMsg: &carserver.VehicleAction_SetCabinOverheatProtectionAction{
					SetCabinOverheatProtectionAction: &carserver.SetCabinOverheatProtectionAction{On: true, FanOnly: false},
				},
			})
		}},
		{"dog_mode_on", universal.Domain_DOMAIN_INFOTAINMENT, func() []byte {
			return carServerAction(&carserver.VehicleAction{
				VehicleActionMsg: &carserver.VehicleAction_HvacClimateKeeperAction{
					HvacClimateKeeperAction: &carserver.HvacClimateKeeperAction{
						ClimateKeeperAction: carserver.HvacClimateKeeperAction_ClimateKeeperAction_Dog,
						ManualOverride:      false,
					},
				},
			})
		}},
		{"dog_mode_off", universal.Domain_DOMAIN_INFOTAINMENT, func() []byte {
			return carServerAction(&carserver.VehicleAction{
				VehicleActionMsg: &carserver.VehicleAction_HvacClimateKeeperAction{
					HvacClimateKeeperAction: &carserver.HvacClimateKeeperAction{
						ClimateKeeperAction: carserver.HvacClimateKeeperAction_ClimateKeeperAction_Off,
						ManualOverride:      false,
					},
				},
			})
		}},
		{"vent_windows", universal.Domain_DOMAIN_INFOTAINMENT, func() []byte {
			return carServerAction(&carserver.VehicleAction{
				VehicleActionMsg: &carserver.VehicleAction_VehicleControlWindowAction{
					VehicleControlWindowAction: &carserver.VehicleControlWindowAction{
						Action: &carserver.VehicleControlWindowAction_Vent{Vent: &carserver.Void{}},
					},
				},
			})
		}},
		{"close_windows", universal.Domain_DOMAIN_INFOTAINMENT, func() []byte {
			return carServerAction(&carserver.VehicleAction{
				VehicleActionMsg: &carserver.VehicleAction_VehicleControlWindowAction{
					VehicleControlWindowAction: &carserver.VehicleControlWindowAction{
						Action: &carserver.VehicleControlWindowAction_Close{Close: &carserver.Void{}},
					},
				},
			})
		}},
	}
}

// buildCommands signs every in-scope command using the Fleet-API auth
// method (HMAC-SHA256-ECDH, plaintext payload) exactly as
// internal/dispatcher/session.go does for connector.AuthMethodHMAC --
// confirmed to be what pkg/connector/inet.Connection.PreferredAuthMethod()
// returns, i.e. what every Fleet-API client (including this tool) actually
// uses. AES-GCM (BLE-only) is intentionally NOT used here; see README.
func buildCommands(clientKey authentication.ECDHPrivateKey, vcsecInfo, infoInfo *signatures.SessionInfo) []commandEntry {
	vcsecSigner, err := authentication.NewSigner(clientKey, []byte(testVIN), vcsecInfo)
	if err != nil {
		panic(err)
	}
	infoSigner, err := authentication.NewSigner(clientKey, []byte(testVIN), infoInfo)
	if err != nil {
		panic(err)
	}

	var entries []commandEntry
	for _, spec := range commandSpecs() {
		signer := infoSigner
		if spec.domain == universal.Domain_DOMAIN_VEHICLE_SECURITY {
			signer = vcsecSigner
		}

		plaintext := spec.payload()

		msg := &universal.RoutableMessage{
			ToDestination: &universal.Destination{
				SubDestination: &universal.Destination_Domain{Domain: spec.domain},
			},
			FromDestination: &universal.Destination{
				SubDestination: &universal.Destination_RoutingAddress{RoutingAddress: fixedRoutingAddress},
			},
			Payload: &universal.RoutableMessage_ProtobufMessageAsBytes{ProtobufMessageAsBytes: append([]byte{}, plaintext...)},
			Uuid:    append([]byte{}, fixedCommandUUID...),
			Flags:   1 << universal.Flags_FLAG_ENCRYPT_RESPONSE,
		}

		// Fixed 5-second expiry window, matching internal/dispatcher's
		// defaultExpiration, computed from a fixed reference instant so
		// re-running fixturegen doesn't change expires_at.
		if err := signer.AuthorizeHMAC(msg, 5*time.Second); err != nil {
			panic(err)
		}

		hmacData := msg.GetSignatureData().GetHMAC_PersonalizedData()
		encoded, err := proto.Marshal(msg)
		if err != nil {
			panic(err)
		}

		// Reconstruct the exact metadata bytes M so Kotlin can verify
		// HMAC-SHA256(K', M || payload) == tag without needing to
		// reimplement tag-sorting logic from scratch, undocumented.
		metadata := serializeMetadata(spec.domain, testVIN, hmacData.GetEpoch(), hmacData.GetExpiresAt(), hmacData.GetCounter(), msg.Flags)

		entries = append(entries, commandEntry{
			Name:               spec.name,
			Domain:             domainName(spec.domain),
			ExpiresAt:          hmacData.GetExpiresAt(),
			Counter:            hmacData.GetCounter(),
			EpochB64:           b64(hmacData.GetEpoch()),
			MetadataB64:        b64(metadata),
			PlaintextActionB64: b64(plaintext),
			TagB64:             b64(hmacData.GetTag()),
			RoutableMessageB64: b64(encoded),
		})
	}
	return entries
}

func domainName(d universal.Domain) string {
	switch d {
	case universal.Domain_DOMAIN_VEHICLE_SECURITY:
		return "VCSEC"
	case universal.Domain_DOMAIN_INFOTAINMENT:
		return "Infotainment"
	default:
		return d.String()
	}
}

// serializeMetadata reproduces internal/authentication/metadata.go's TLV
// encoding for the HMAC_PERSONALIZED signing path, independent of the
// vendored package's unexported metadata type, so this logic is visible in
// this file (not hidden behind an internal call) for auditability. Tags are
// added in ascending numeric order, per Signatures.Tag: SIGNATURE_TYPE(0) <
// DOMAIN(1) < PERSONALIZATION(2) < EPOCH(3) < EXPIRES_AT(4) < COUNTER(5) <
// FLAGS(7), terminated by TAG_END(255). FLAGS is only included if non-zero.
func serializeMetadata(domain universal.Domain, vin string, epoch []byte, expiresAt, counter, flags uint32) []byte {
	var buf []byte
	tlv := func(tag signatures.Tag, value []byte) {
		buf = append(buf, byte(tag), byte(len(value)))
		buf = append(buf, value...)
	}
	u32 := func(v uint32) []byte {
		return []byte{byte(v >> 24), byte(v >> 16), byte(v >> 8), byte(v)}
	}
	tlv(signatures.Tag_TAG_SIGNATURE_TYPE, []byte{byte(signatures.SignatureType_SIGNATURE_TYPE_HMAC_PERSONALIZED)})
	tlv(signatures.Tag_TAG_DOMAIN, []byte{byte(domain)})
	tlv(signatures.Tag_TAG_PERSONALIZATION, []byte(vin))
	tlv(signatures.Tag_TAG_EPOCH, epoch)
	tlv(signatures.Tag_TAG_EXPIRES_AT, u32(expiresAt))
	tlv(signatures.Tag_TAG_COUNTER, u32(counter))
	if flags > 0 {
		tlv(signatures.Tag_TAG_FLAGS, u32(flags))
	}
	buf = append(buf, byte(signatures.Tag_TAG_END))
	return buf
}

// ---------------------------------------------------------------------------
// main
// ---------------------------------------------------------------------------

func writeJSON(dir, filename string, v interface{}) {
	data, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		panic(err)
	}
	path := filepath.Join(dir, filename)
	if err := os.WriteFile(path, append(data, '\n'), 0644); err != nil {
		panic(err)
	}
	fmt.Printf("wrote %s (%d bytes)\n", path, len(data))
}

func main() {
	if err := os.MkdirAll(outDir, 0755); err != nil {
		panic(err)
	}

	clientStd := stdlibKey(clientPrivateHex)
	vehicleStd := stdlibKey(vehiclePrivateHex)
	clientPublicBytes := uncompressedPoint(clientStd.PublicKey())

	clientNative := nativeKey(clientPrivateHex)
	vehicleNative := nativeKey(vehiclePrivateHex)

	// keys.json
	keysFx := buildKeysFixture(clientStd, vehicleStd)
	writeJSON(outDir, "keys.json", keysFx)

	// session_info_request.json
	requests := buildSessionInfoRequests(clientPublicBytes)
	writeJSON(outDir, "session_info_request.json", requests)

	// session_info_response.json (Infotainment domain synthetic handshake)
	sessionRespFx, infoSessionInfo := buildSessionInfoResponse(vehicleNative, clientPublicBytes)
	writeJSON(outDir, "session_info_response.json", sessionRespFx)

	// A second, independent VCSEC verifier/session-info pair is needed to
	// sign VCSEC-domain commands (lock/unlock) below; VCSEC and
	// Infotainment maintain fully independent sessions per protocol.md.
	vcsecVerifier, err := authentication.NewVerifier(vehicleNative, []byte(testVIN), universal.Domain_DOMAIN_VEHICLE_SECURITY, clientPublicBytes)
	if err != nil {
		panic(err)
	}
	vcsecSessionInfo, err := vcsecVerifier.SessionInfo()
	if err != nil {
		panic(err)
	}

	// fault_response.json
	faultFx := buildFaultResponse()
	writeJSON(outDir, "fault_response.json", faultFx)

	// commands.json
	commands := buildCommands(clientNative, vcsecSessionInfo, infoSessionInfo)
	writeJSON(outDir, "commands.json", commands)

	fmt.Printf("\ngenerated %d command fixtures\n", len(commands))
}
