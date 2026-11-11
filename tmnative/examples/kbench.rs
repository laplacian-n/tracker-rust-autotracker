//! Kernel micro-benchmark: cargo run --release --example kbench
use std::time::Instant;

fn main() {
    let mut seed = 12345u64;
    let mut rnd = || { seed ^= seed << 13; seed ^= seed >> 7; seed ^= seed << 17; seed };
    let scenes: Vec<(usize, usize)> = match std::env::var("KB_SCENES") {
        Ok(v) => v.split(',').map(|s| { let mut it = s.split('x'); (it.next().unwrap().parse().unwrap(), it.next().unwrap().parse().unwrap()) }).collect(),
        Err(_) => vec![(21, 41), (41, 101), (61, 201), (81, 401), (161, 41), (161, 101), (161, 185)],
    };
    let modes: Vec<i32> = std::env::var("KB_MODES").map(|v| v.split(',').map(|m| m.parse().unwrap()).collect()).unwrap_or(vec![4, 10, 6, 9, 11, 12]);
    for &(tpl, search) in &scenes {
        let w_test = search + tpl;
        let h_test = search + tpl;
        let target: Vec<i32> = (0..w_test * h_test).map(|_| (rnd() & 0xffffff) as i32).collect();
        let n = tpl * tpl;
        let tr: Vec<i32> = (0..n).map(|_| (rnd() & 0xff) as i32).collect();
        let tg: Vec<i32> = (0..n).map(|_| (rnd() & 0xff) as i32).collect();
        let tb: Vec<i32> = (0..n).map(|_| (rnd() & 0xff) as i32).collect();
        let c = tpl as f64 / 2.0;
        let transp: Vec<u8> = (0..n).map(|k| {
            let (x, y) = ((k % tpl) as f64 + 0.5 - c, (k / tpl) as f64 + 0.5 - c);
            (x * x + y * y > c * c) as u8
        }).collect();
        let mut line = format!("tpl={tpl:3} search={search:3}");
        let mut reference = None;
        for &mode in &modes {
            let reps = (2e8 / (search * search * n) as f64).max(3.0) as usize;
            let t0 = Instant::now();
            let mut r = (0, 0, 0);
            for _ in 0..reps {
                r = tmnative::bench_run(mode, &target, w_test, &tr, &tg, &tb, &transp, tpl, tpl, search as i32, search as i32);
            }
            let us = t0.elapsed().as_secs_f64() * 1e6 / reps as f64;
            if *reference.get_or_insert(r) != r { line += " MISMATCH"; }
            line += &format!("  m{mode}={us:9.1}us");
        }
        println!("{line}");
    }
}
