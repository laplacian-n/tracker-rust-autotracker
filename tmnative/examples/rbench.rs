//! Times the sub-pixel refine on a blurred ball, template sizes like Tracker's.
use std::time::Instant;
fn main() {
    for &(wt, ht) in &[(21usize, 21usize), (41, 41), (81, 81), (162, 158)] {
        let (w, h) = (wt + 60, ht + 60);
        let (cx, cy, r) = (w as f64 / 2.0 + 0.37, h as f64 / 2.0 - 0.21, wt as f64 * 0.4);
        let px = |x: f64, y: f64| {
            let d = ((x - cx).powi(2) + (y - cy).powi(2)).sqrt() - r;
            let a = 1.0 / (1.0 + (d / 1.2).exp());
            let c = |lo: f64, hi: f64| (lo + (hi - lo) * a) as i32;
            (c(40.0, 200.0), c(60.0, 30.0), c(90.0, 190.0))
        };
        let target: Vec<i32> = (0..w * h).map(|i| { let (r, g, b) = px((i % w) as f64, (i / w) as f64); (r << 16) | (g << 8) | b }).collect();
        let (xm, ym) = (30i32, 30i32);
        let (mut tr, mut tg, mut tb, mut tp) = (vec![], vec![], vec![], vec![]);
        let (tcx, tcy) = ((wt as f64 - 1.0) / 2.0, (ht as f64 - 1.0) / 2.0);
        for j in 0..ht { for i in 0..wt {
            let (r, g, b) = px(i as f64 + cx - tcx, j as f64 + cy - tcy);
            tr.push(r); tg.push(g); tb.push(b);
            let inside = ((i as f64 - tcx) / (wt as f64 / 2.0)).powi(2) + ((j as f64 - tcy) / (ht as f64 / 2.0)).powi(2) <= 1.0;
            tp.push(if inside { 0u8 } else { 1 });
        }}
        let xm = xm + (cx - tcx - 30.0).round() as i32 - 0; let ym = ym + (cy - tcy - 30.0).round() as i32;
        let mut res = None;
        let n = 200;
        let t = Instant::now();
        for _ in 0..n { res = tmnative::bench_refine(&target, w, &tr, &tg, &tb, &tp, wt, ht, xm, ym); }
        println!("{wt}x{ht}: {:.1} us/refine  result {:?}", t.elapsed().as_secs_f64() * 1e6 / n as f64, res);
    }
}
