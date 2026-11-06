// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

#include "../QRCode/qrcodegen.h"

// Swift does not import the upstream macro because it expands another function-like macro.
static inline size_t bipeQRCodeBufferLength(void) { return qrcodegen_BUFFER_LEN_MAX; }
