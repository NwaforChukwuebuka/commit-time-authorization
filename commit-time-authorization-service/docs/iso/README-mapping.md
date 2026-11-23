# ISO 20022 field mapping

Messages are parsed and built with `pw-iso20022` `SRU2025-10.4.1`. This service accepts one credit transfer per message. `NbOfTxs`, when present, must be `1`, and the transaction list must contain one element. A batch is a mapping error (`422 ISO_MAPPING`). It is not a commit rejection.

Account ids are the ledger account UUIDs, carried in `Acct/Id/Othr/Id`. The service does not resolve IBANs. ISO's 35-character limit on ids is not enforced: `event_id` and `auth_ref` are 255 characters in the ledger.

`RmtInf/Strd` is 140 characters, which is shorter than a signed debit attestation. The attestation JSON is carried in `CdtTrfTxInf/SplmtryData/Envlp/Attestation` (`urn:mit8212:cta`). Bank A writes it when the debit commits. Bank B reads it back before `commitCredit`. A message without that element has no attestation.

## Versions

| Message | Version | Java type | Direction |
| --- | --- | --- | --- |
| pain.001 | 001.11 | `MxPain00100111` | Inbound. Issues a grant. |
| pacs.008 | 001.07 | `MxPacs00800107` | Inbound payment event, and built back from a `PaymentEventCommand`. |
| pacs.002 | 001.10 | `MxPacs00200110` | Outbound from the latest `decision` row. |

pain.001.001.11 is the version the Prowide creation example builds. pacs.008.001.07 is the version of the sample in `iso-examples`. That sample has debtor and creditor names and an `EndToEndId`, and no `DbtrAcct` or `CdtrAcct`, so it is a parse check only. It is not posted. pacs.002.001.10 is the status report this service writes. The examples repo has no pacs.002 sample.

## pain.001 to a grant

`POST /api/v1/iso/pain.001` with `Content-Type: application/xml`. The response is the JSON grant (`201`). The handler does not authorize the payment.

| ISO | Ledger |
| --- | --- |
| `PmtInf/DbtrAcct/Id/Othr/Id` | Payer account id |
| `CdtTrfTxInf/CdtrAcct/Id/Othr/Id` | Payee account id |
| `CdtTrfTxInf/Amt/InstdAmt` | Amount and currency, normalized to 4 decimal places |
| `CdtTrfTxInf/PmtId/EndToEndId` | `auth_ref` when present. When absent, the service assigns `AUTH-` plus a UUID, as `POST /api/v1/grants` does. |

## pacs.008 to a payment event

`POST /api/v1/iso/pacs.008` with `Content-Type: application/xml`. The handler checks the shape, enqueues a `PaymentEventCommand`, and returns `202`. It does not call `commitIntraBank`. `kind` is `LEGIT`. `now` is the time the request is accepted, not a field in the XML.

| ISO | Ledger |
| --- | --- |
| `PmtId/InstrId` | `event_id` (P1). A repeated instruction is the same event. |
| `GrpHdr/MsgId` | Transport id. It becomes `event_id` only when `InstrId` is absent. A new `MsgId` with the same `InstrId` is not a new payment. |
| `PmtId/EndToEndId` | `auth_ref` (P3). Required. |
| `DbtrAcct/Id/Othr/Id` | Payer account id |
| `CdtrAcct/Id/Othr/Id` | Payee account id |
| `IntrBkSttlmAmt` | Amount and currency |
| `AccptncDtTm`, else `GrpHdr/CreDtTm` | `issuedAt`. Send the timestamp with a UTC offset (`Z`). |
| `SplmtryData/Envlp/Attestation` | Debit attestation JSON. Absent on an intra-bank message. Present on a payer's outbound pacs.008. |

The same mapping is used in reverse when a command is written as pacs.008. `InstrId` and `MsgId` are both set to `event_id`, `EndToEndId` to `auth_ref`, and `AccptncDtTm` to `issuedAt`. When the command carries an attestation, it is written into `SplmtryData` and read back on parse.

## decision to pacs.002

`GET /api/v1/decisions?eventId=` with `Accept: application/xml` returns one pacs.002 for the latest decision. Any other `Accept` value, including `*/*`, returns the JSON list of every attempt. A replay does not overwrite the first row.

| Decision | pacs.002 |
| --- | --- |
| `COMMITTED` | `TxSts` `ACCP` |
| `DUPLICATE`, `REJECTED`, `DECLINED` | `TxSts` `RJCT` |
| `decision.reason` | `StsRsnInf/Rsn/Prtry` (`OK`, `P1_EVENT_ALREADY_COMMITTED`, `P4_PAYLOAD_MISMATCH`, and the other commit reasons) |
| `event_id` | `OrgnlInstrId` |
| `auth_ref` | `OrgnlEndToEndId` |

A duplicate delivery is `RJCT` with `P1_EVENT_ALREADY_COMMITTED`. The original commit remains the earlier `ACCP` row. The XML response shows only the latest one.
