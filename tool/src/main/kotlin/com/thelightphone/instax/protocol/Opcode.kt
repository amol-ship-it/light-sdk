package com.thelightphone.instax.protocol

/** The opcodes this tool uses, verified against the pinned javl/InstaxBLE
 *  reference (Types.py EventType). Fixtures are ground truth. */
enum class Opcode(val op1: Int, val op2: Int) {
    SUPPORT_FUNCTION_INFO(0, 2),
    PRINT_IMAGE_DOWNLOAD_START(16, 0),
    PRINT_IMAGE_DOWNLOAD_DATA(16, 1),
    PRINT_IMAGE_DOWNLOAD_END(16, 2),
    PRINT_IMAGE_DOWNLOAD_CANCEL(16, 3),
    PRINT_IMAGE(16, 128);

    companion object {
        fun from(op1: Int, op2: Int): Opcode? =
            entries.find { it.op1 == op1 && it.op2 == op2 }
    }
}
