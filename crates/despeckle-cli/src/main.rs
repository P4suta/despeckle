//! Entry point for the `despeckle` CLI binary.

#![allow(
    clippy::redundant_pub_crate,
    reason = "pub(crate) は意図表現。unreachable_pub と redundant_pub_crate の衝突は前者を優先"
)]

use anyhow::Result;
use clap::Parser;
use mimalloc::MiMalloc;

#[global_allocator]
static GLOBAL: MiMalloc = MiMalloc;

mod cli;
mod logging;
mod report;
mod runner;

fn main() -> Result<()> {
    logging::init();
    let args = cli::Args::parse();
    runner::run(&args)
}
