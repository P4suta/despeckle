//! End-to-end test: run the binary against a temp-directory pair of PBM
//! pages and confirm every input gets a same-named output back.

#![allow(
    clippy::expect_used,
    clippy::unwrap_used,
    clippy::panic,
    reason = "integration test fixture: a setup-step panic is the desired failure mode"
)]

use std::fs;
use std::path::Path;

use assert_cmd::Command;
use tempfile::tempdir;

fn write_blank_pbm(path: &Path, width: u32, height: u32) {
    let bytes_per_row = (width as usize).div_ceil(8);
    let mut bytes = format!("P4\n{width} {height}\n").into_bytes();
    bytes.extend(vec![0u8; bytes_per_row * height as usize]);
    fs::write(path, bytes).expect("write pbm");
}

#[test]
fn directory_copy_preserves_filenames() {
    let tmp = tempdir().expect("tempdir");
    let input = tmp.path().join("input");
    let output = tmp.path().join("output");
    fs::create_dir_all(&input).expect("mkdir input");

    for i in 1..=3 {
        write_blank_pbm(&input.join(format!("page-{i:02}.pbm")), 16, 16);
    }

    Command::cargo_bin("despeckle")
        .expect("cargo bin")
        .arg(&input)
        .arg(&output)
        .arg("--force")
        .assert()
        .success();

    let mut names: Vec<String> = fs::read_dir(&output)
        .expect("read output")
        .map(|e| {
            e.expect("dir entry")
                .file_name()
                .into_string()
                .expect("utf-8 filename")
        })
        .collect();
    names.sort();

    assert_eq!(names, vec!["page-01.pbm", "page-02.pbm", "page-03.pbm"]);
}
