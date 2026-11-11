//! Checkerboard detector test on rendered frames with known corner positions.
//!
//!   cargo run --release --example cbench
//!
//! A 1080x1920 camera (f = 1500 px) looks at a printed board (9x6 inner corners)
//! that is tilted, turned and placed so its squares are a given size in pixels.
//! The frame is supersampled, blurred, gets noise and a cluttered background.
//! Prints how many corners were found and their error against the truth.

use std::time::Instant;
use tmnative::checker;

struct Rng(u64);
impl Rng {
    fn next(&mut self) -> f64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        (self.0 >> 11) as f64 / (1u64 << 53) as f64
    }
    fn gauss(&mut self) -> f64 {
        let (u, v) = (self.next().max(1e-12), self.next());
        (-2.0 * u.ln()).sqrt() * (std::f64::consts::TAU * v).cos()
    }
}

type M3 = [[f64; 3]; 3];

fn mul(a: &M3, b: &M3) -> M3 {
    let mut r = [[0.0; 3]; 3];
    for i in 0..3 {
        for j in 0..3 {
            for k in 0..3 {
                r[i][j] += a[i][k] * b[k][j];
            }
        }
    }
    r
}

fn inv(m: &M3) -> M3 {
    let [[a, b, c], [d, e, f], [g, h, i]] = *m;
    let det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
    [
        [(e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det],
        [(f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det],
        [(d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det],
    ]
}

fn apply(m: &M3, x: f64, y: f64) -> (f64, f64) {
    let w = m[2][0] * x + m[2][1] * y + m[2][2];
    ((m[0][0] * x + m[0][1] * y + m[0][2]) / w, (m[1][0] * x + m[1][1] * y + m[1][2]) / w)
}

/// world (mm, board plane) -> image homography
fn homography(tilt_deg: f64, yaw_deg: f64, roll_deg: f64, square_px: f64, square_mm: f64) -> M3 {
    let f = 1500.0;
    let (cx, cy) = (540.0, 960.0);
    let (t, y, r) = (tilt_deg.to_radians(), yaw_deg.to_radians(), roll_deg.to_radians());
    // rotation: roll about z, then tilt about x, then yaw about y
    let rz = [[r.cos(), -r.sin(), 0.0], [r.sin(), r.cos(), 0.0], [0.0, 0.0, 1.0]];
    let rx = [[1.0, 0.0, 0.0], [0.0, t.cos(), -t.sin()], [0.0, t.sin(), t.cos()]];
    let ry = [[y.cos(), 0.0, y.sin()], [0.0, 1.0, 0.0], [-y.sin(), 0.0, y.cos()]];
    let rot = mul(&ry, &mul(&rx, &rz));
    // distance so that one square at the board centre is about square_px wide
    let z = f * square_mm / square_px;
    // board centre (5 squares x 3.5 squares) at the optical axis
    let (bx, by) = (5.0 * square_mm, 3.5 * square_mm);
    let t3 = [
        -(rot[0][0] * bx + rot[0][1] * by),
        -(rot[1][0] * bx + rot[1][1] * by),
        z - (rot[2][0] * bx + rot[2][1] * by),
    ];
    let k = [[f, 0.0, cx], [0.0, f, cy], [0.0, 0.0, 1.0]];
    let rt = [[rot[0][0], rot[0][1], t3[0]], [rot[1][0], rot[1][1], t3[1]], [rot[2][0], rot[2][1], t3[2]]];
    mul(&k, &rt)
}

fn render(h: &M3, sq: f64, blur_px: f64, noise: f64, rng: &mut Rng) -> Vec<f32> {
    let (w, hh) = (1080usize, 1920usize);
    let hi = inv(h);
    // clutter: random dark/bright rectangles and a gradient
    let rects: Vec<(f64, f64, f64, f64, f64)> = (0..60)
        .map(|_| (rng.next() * 1080.0, rng.next() * 1920.0, 20.0 + rng.next() * 200.0, 20.0 + rng.next() * 200.0, rng.next() * 255.0))
        .collect();
    let mut img = vec![0f32; w * hh];
    for y in 0..hh {
        for x in 0..w {
            let mut acc = 0.0;
            for s in 0..16 {
                let px = x as f64 + ((s % 4) as f64 + 0.5) / 4.0 - 0.5;
                let py = y as f64 + ((s / 4) as f64 + 0.5) / 4.0 - 0.5;
                let (u, v) = apply(&hi, px, py);
                let (i, j) = ((u / sq).floor(), (v / sq).floor());
                let val = if (0.0..10.0).contains(&i) && (0.0..7.0).contains(&j) {
                    if (i as i64 + j as i64) % 2 == 0 { 25.0 } else { 225.0 }
                } else if (-1.0..11.0).contains(&i) && (-1.0..8.0).contains(&j) {
                    235.0 // paper margin
                } else {
                    let mut b = 90.0 + 60.0 * (px / 1080.0) + 30.0 * (py / 1920.0);
                    for r in &rects {
                        if px > r.0 && px < r.0 + r.2 && py > r.1 && py < r.1 + r.3 {
                            b = r.4;
                        }
                    }
                    b
                };
                acc += val;
            }
            img[y * w + x] = (acc / 16.0) as f32;
        }
    }
    // optical blur
    if blur_px > 0.0 {
        let r = (3.0 * blur_px).ceil() as isize;
        let k: Vec<f64> = (-r..=r).map(|i| (-(i * i) as f64 / (2.0 * blur_px * blur_px)).exp()).collect();
        let ks: f64 = k.iter().sum();
        let mut tmp = img.clone();
        for y in 0..hh {
            for x in 0..w {
                let mut a = 0.0;
                for (t, kv) in k.iter().enumerate() {
                    let xx = (x as isize + t as isize - r).clamp(0, w as isize - 1) as usize;
                    a += kv * img[y * w + xx] as f64;
                }
                tmp[y * w + x] = (a / ks) as f32;
            }
        }
        for y in 0..hh {
            for x in 0..w {
                let mut a = 0.0;
                for (t, kv) in k.iter().enumerate() {
                    let yy = (y as isize + t as isize - r).clamp(0, hh as isize - 1) as usize;
                    a += kv * tmp[yy * w + x] as f64;
                }
                img[y * w + x] = (a / ks) as f32;
            }
        }
    }
    for p in img.iter_mut() {
        *p = (*p as f64 + noise * rng.gauss()).clamp(0.0, 255.0).round() as f32;
    }
    img
}

fn main() {
    let mut rng = Rng(0x9e3779b97f4a7c15);
    println!("{:>6} {:>5} {:>5} {:>5} {:>6} | {:>5} {:>8} {:>8} {:>7}", "sq px", "tilt", "yaw", "blur", "noise", "found", "rms px", "max px", "ms");
    let cases = [
        (40.0, 0.0, 0.0, 1.0, 2.0),
        (20.0, 0.0, 0.0, 1.0, 2.0),
        (12.0, 0.0, 0.0, 0.8, 2.0),
        (8.0, 0.0, 0.0, 0.7, 2.0),
        (30.0, 20.0, 10.0, 1.2, 3.0),
        (30.0, 40.0, 15.0, 1.2, 3.0),
        (20.0, 50.0, 0.0, 1.0, 3.0),
        (60.0, 30.0, -25.0, 2.0, 4.0),
        (15.0, 25.0, 20.0, 1.5, 6.0),
    ];
    for &(sq_px, tilt, yaw, blur, noise) in &cases {
        let sq = 20.0;
        let h = homography(tilt, yaw, 7.0, sq_px, sq);
        let img = render(&h, sq, blur, noise, &mut rng);
        let t0 = Instant::now();
        let found = checker::find(&img, 1080, 1920);
        let ms = t0.elapsed().as_secs_f64() * 1e3;
        // truth: inner corners (1..9, 1..6) in board squares
        let truth: Vec<(f64, f64)> = (1..7).flat_map(|j| (1..10).map(move |i| (i, j))).map(|(i, j)| apply(&h, i as f64 * sq, j as f64 * sq)).collect();
        let mut se = 0.0;
        let mut mx: f64 = 0.0;
        let mut matched = 0;
        for c in &found {
            let d = truth.iter().map(|t| (t.0 - c.x).hypot(t.1 - c.y)).fold(f64::MAX, f64::min);
            if d < sq_px * 0.3 {
                matched += 1;
                se += d * d;
                mx = mx.max(d);
            }
        }
        let rms = if matched > 0 { (se / matched as f64).sqrt() } else { f64::NAN };
        let dims = if found.is_empty() {
            String::new()
        } else {
            format!(" ({}x{})", found.iter().map(|c| c.i).max().unwrap() + 1, found.iter().map(|c| c.j).max().unwrap() + 1)
        };
        println!(
            "{:>6.0} {:>5.0} {:>5.0} {:>5.1} {:>6.1} | {:>2}/54{} {:>8.3} {:>8.3} {:>7.0}{}",
            sq_px, tilt, yaw, blur, noise, matched, if found.len() != matched { format!(" +{} wrong", found.len() - matched) } else { String::new() }, rms, mx, ms, dims
        );
    }
}
