package com.amolpurohit.tesla.vehicle

sealed interface VehicleUiState {
    data object NoCredentials : VehicleUiState
    data object Loading : VehicleUiState
    data class Ready(val state: VehicleState, val updatedAtMs: Long, val stale: Boolean) : VehicleUiState
    data class Asleep(val cached: VehicleState?, val updatedAtMs: Long?) : VehicleUiState
    data class Error(val kind: ErrorKind, val cached: VehicleState?, val updatedAtMs: Long?) : VehicleUiState
}

enum class ErrorKind { Offline, AuthExpired, KeyNotEnrolled, RateLimited, WakeTimeout, Unknown }
