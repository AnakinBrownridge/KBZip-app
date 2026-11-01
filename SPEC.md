# KBZip Format Specification, version 1

KBZip (`.kbz`) is an open, lossless archive format made for Karen Benny.
This document is the normative description of version 1. Anyone may implement it.

- Suggested MIME type: `application/x-kbzip`
- File extension: `.kbz`
- All multi-byte integers are **little-endian**. `u8/u16/u32/u64` are unsigned.
- **varint** is unsigned LEB128: 7 bits per byte, least-significant group first, high bit set on every byte except the last. Maximum 10 bytes (64 bits).
- All text is UTF-8.

## 1. Goals

1. Lossless: extracted bytes are identical to the originals, verified by CRC-32.
2. Scales to millions of files: a compact index at the end of the file, small files packed into shared blocks, any single file extractable without reading the rest.
3. Open: this spec plus MIT-licensed code. Reserved fields and codec IDs allow evolution.

## 2. Overall layout

```
+---------------------------+
| Header                    |
+---------------------------+
| Block 0                   |
| Block 1                   |
| ...                       |
| Block N-1                 |
+---------------------------+
| Index (raw DEFLATE)       |
+---------------------------+
| Footer (24 bytes)         |
+---------------------------+
```

A writer needs only sequential output. A reader needs random access (seek).

## 3. Header

| Offset | Size | Field | Notes |
|-------:|-----:|-------|-------|
| 0 | 4 | magic | ASCII `KBZ1` |
| 4 | 1 | version | `1`. Readers MUST reject versions they do not know. |
| 5 | 1 | flags | MUST be `0` in v1. Readers MUST ignore unknown bits only if a future version says so; in v1, reject non-zero. |
| 6 | 2 | dedicationLen (u16) | Length in bytes of the dedication, 0 to 65535 |
| 8 | dedicationLen | dedication | Optional UTF-8 text, e.g. `Made for Karen Benny`. Purely informational. |

Blocks begin immediately after the dedication.

## 4. Data blocks

File contents are concatenated, in entry order, into one logical byte stream. That stream is cut into **blocks** of at most 4 MiB (4,194,304 bytes) of uncompressed data. Every block except the last is exactly the writer's block size (a writer SHOULD use 4 MiB). A file may begin mid-block and may span several blocks. Small files therefore share blocks, so millions of tiny files do not create millions of compression streams.

Each block is stored as:

| Size | Field | Notes |
|-----:|-------|-------|
| 4 | rawLen (u32) | Uncompressed length, 1 to 67,108,864 (64 MiB) |
| 4 | packedLen (u32) | Length of payload in bytes |
| 1 | codec (u8) | `0` = stored, `1` = raw DEFLATE (RFC 1951, no zlib/gzip wrapper). Other values reserved (planned: `2` = Zstandard, `3` = LZMA2). |
| 4 | crc32 (u32) | CRC-32 (IEEE 802.3, same as zlib) of the **uncompressed** data |
| packedLen | payload | |

If codec is `0`, `packedLen` MUST equal `rawLen`. Writers SHOULD use codec `0` when compression does not shrink the block.

## 5. Index

The index is raw DEFLATE-compressed. Its location and checksum are in the footer. After decompression it contains:

```
varint  blockCount
repeat blockCount:
    varint  blockFileOffset     // absolute offset of the block header in the .kbz file
    varint  blockRawLen         // must equal the rawLen in that block's header
varint  entryCount
repeat entryCount:
    u8      type                // 0 = file, 1 = directory
    varint  pathLen
    bytes   path                // UTF-8
    varint  mtime               // seconds since Unix epoch, UTC (0 if unknown)
    if type == 0:
        varint  size            // uncompressed size in bytes
        varint  startBlock      // index of the block containing the first byte
        varint  offset          // byte offset of the first byte within that block's uncompressed data
        u32     crc32           // CRC-32 of the whole file
```

A file's bytes are read starting at `offset` in block `startBlock`, continuing at offset 0 of each following block, until `size` bytes have been read. Zero-length files have `size = 0`; their `startBlock` and `offset` are ignored.

Because the index is always at the end and holds block offsets, a reader can list everything, and extract any one file, by reading the footer, the index, and only the blocks that file touches.

## 6. Footer (last 24 bytes of the file)

| Offset | Size | Field |
|-------:|-----:|-------|
| 0 | 8 | indexOffset (u64): absolute offset of the compressed index |
| 8 | 4 | indexPackedLen (u32) |
| 12 | 4 | indexRawLen (u32) |
| 16 | 4 | indexCrc32 (u32): CRC-32 of the uncompressed index |
| 20 | 4 | footerMagic: ASCII `1ZBK` (the header magic reversed) |

The compressed index MUST end exactly where the footer begins: `indexOffset + indexPackedLen == fileSize - 24`.

## 7. Paths

- Separator is `/`. Paths are relative, with no leading or trailing `/`.
- No empty segments, no `.` or `..` segments, no backslash, no NUL.
- Paths MUST be unique within an archive.
- A directory entry is optional for directories that only contain files; writers SHOULD emit entries for empty directories.
- **Readers MUST reject archives containing paths that violate these rules** (this prevents "zip-slip" path traversal). Readers on Windows should additionally reject or sanitize reserved names and `:`.

## 8. Reader requirements

A conforming reader MUST:

1. Check header magic and version, footer magic, and that offsets lie inside the file.
2. Verify the index CRC-32 before trusting it.
3. Verify each block's CRC-32 after decompression and the file CRC-32 after extracting a file.
4. Treat any mismatch as corruption and fail that operation (never silently emit bad data).
5. Enforce the size limits above before allocating memory.

## 9. Writer requirements

A conforming writer MUST write the footer last, only after all blocks and the index. An archive without a valid footer is incomplete and unreadable by design.

## 10. Versioning and extension

- New codecs get new codec IDs; v1 readers reject codec IDs they do not know.
- Changes that older readers cannot safely ignore bump the `version` byte.
- The `flags` byte is reserved for future use.

## 11. Limits (v1)

| Item | Limit |
|------|------|
| Entries | Limited by memory for the index (about 40 to 80 bytes per entry before compression) |
| File size | 2^63 - 1 |
| Block raw size | 64 MiB max, 4 MiB recommended |
| Dedication | 65,535 bytes |
| Archive size | 2^63 - 1 |
