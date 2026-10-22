use std::{
  fs,
  sync::atomic::{AtomicU64, Ordering},
  time::{SystemTime, UNIX_EPOCH},
};

use cloud_notes_nbstore::DocUpdate as NbDocUpdate;
use chrono::{DateTime, Utc};

use crate::{UniffiError, cache, ffi_types::DocUpdate, storage::new_doc_storage_pool};

static TEST_COUNTER: AtomicU64 = AtomicU64::new(0);

fn unique_id(prefix: &str) -> String {
  let counter = TEST_COUNTER.fetch_add(1, Ordering::Relaxed);
  let now = SystemTime::now()
    .duration_since(UNIX_EPOCH)
    .expect("system clock before unix epoch")
    .as_nanos();
  format!("{prefix}-{now}-{counter}")
}

#[test]
fn doc_update_roundtrip_base64() {
  let timestamp = DateTime::<Utc>::from_timestamp_millis(1_700_000_000_000)
    .unwrap()
    .naive_utc();
  let original = NbDocUpdate {
    doc_id: "doc-1".to_string(),
    timestamp,
    bin: vec![1, 2, 3, 4, 5],
  };

  let encoded: DocUpdate = original.into();
  let decoded = NbDocUpdate::try_from(encoded).unwrap();

  assert_eq!(decoded.doc_id, "doc-1");
  assert_eq!(decoded.timestamp, timestamp);
  assert_eq!(decoded.bin, vec![1, 2, 3, 4, 5]);
}

#[test]
fn doc_update_rejects_invalid_base64() {
  let update = DocUpdate {
    doc_id: "doc-2".to_string(),
    timestamp: 0,
    bin: "not-base64!!".to_string(),
  };

  let err = match NbDocUpdate::try_from(update) {
    Ok(_) => panic!("expected base64 decode error"),
    Err(err) => err,
  };
  match err {
    UniffiError::Base64DecodingError(_) => {}
    other => panic!("unexpected error: {other:?}"),
  }
}

#[tokio::test]
async fn decode_blob_data_rejects_out_of_workspace_path() {
  let pool = new_doc_storage_pool();
  let universal_id = unique_id("mobile-doc-outside");
  pool
    .connect(universal_id.clone(), ":memory:".to_string())
    .await
    .expect("connect should succeed");

  let outside_dir = std::env::temp_dir().join(unique_id("mobile-doc-outside-dir"));
  fs::create_dir_all(&outside_dir).expect("create outside dir");
  let outside_file = outside_dir.join("1234567890abcdef.blob");
  fs::write(&outside_file, b"outside").expect("write outside file");
  let token = format!("{}{}", cache::MOBILE_BLOB_FILE_PREFIX, outside_file.display());

  let err = pool
    .decode_blob_data(&universal_id, &token)
    .await
    .expect_err("decode should reject out-of-workspace token");
  let UniffiError::Err(message) = err else {
    panic!("unexpected error kind");
  };
  assert!(message.contains("outside the workspace cache directory"));

  pool.disconnect(universal_id).await.expect("disconnect should succeed");
  let _ = fs::remove_dir_all(outside_dir);
}

/// Phase 0 (cloud-notes-hybrid): a doc written through the same pool API the
/// Kotlin NbStorePlugin calls must survive a full disconnect / new pool (the
/// process-kill equivalent: all handles dropped, only the .db file remains)
/// and read back byte-identical.
#[tokio::test]
async fn doc_persists_across_reopen_byte_identical() {
  let dir = std::env::temp_dir().join(unique_id("mobile-doc-reopen"));
  fs::create_dir_all(&dir).expect("create dir");
  let db = dir.join("space.db").display().to_string();
  let id = unique_id("mobile-reopen");

  // Non-trivial binary payload: every byte value, so a text-encoding bug shows.
  let payload: Vec<u8> = (0..=255u8).chain((0..=255u8).rev()).collect();
  let b64 = base64_simd::STANDARD.encode_to_string(&payload);

  {
    let pool = new_doc_storage_pool();
    pool.connect(id.clone(), db.clone()).await.expect("connect");
    pool.set_space_id(id.clone(), "space".to_string()).await.expect("space id");
    pool.push_update(id.clone(), "doc-a".to_string(), b64.clone()).await.expect("push");
    let ok = pool
      .set_doc_snapshot(
        id.clone(),
        crate::ffi_types::DocRecord { doc_id: "doc-b".to_string(), bin: b64.clone(), timestamp: 1_700_000_000_000 },
      )
      .await
      .expect("snapshot");
    assert!(ok);
    pool.disconnect(id.clone()).await.expect("disconnect");
  }

  let pool = new_doc_storage_pool();
  pool.connect(id.clone(), db).await.expect("reconnect");
  let snap = pool
    .get_doc_snapshot(id.clone(), "doc-b".to_string())
    .await
    .expect("get snapshot")
    .expect("snapshot present after reopen");
  assert_eq!(snap.timestamp, 1_700_000_000_000);
  assert_eq!(base64_simd::STANDARD.decode_to_vec(snap.bin.as_bytes()).unwrap(), payload);

  let updates = pool.get_doc_updates(id.clone(), "doc-a".to_string()).await.expect("updates");
  assert_eq!(updates.len(), 1);
  assert_eq!(base64_simd::STANDARD.decode_to_vec(updates[0].bin.as_bytes()).unwrap(), payload);

  pool.disconnect(id).await.expect("disconnect");
  let _ = fs::remove_dir_all(dir);
}
