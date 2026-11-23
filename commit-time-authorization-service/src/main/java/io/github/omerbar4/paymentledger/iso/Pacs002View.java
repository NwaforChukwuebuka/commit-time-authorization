package io.github.omerbar4.paymentledger.iso;

/** Transaction status, proprietary reason, and the original payment ids read back from a pacs.002. */
public record Pacs002View(String transactionStatus, String reason, String originalInstructionId,
                          String originalEndToEndId) {
}
