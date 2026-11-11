//! Finds a printed checkerboard in a video frame and returns its inner corners
//! (where four squares meet) to sub-pixel accuracy, with their grid indices.
//!
//! 1. Blur at a few scales; at each, corners are saddle points of the
//!    brightness: strongly negative Hessian determinant.
//! 2. From a strong candidate, its four nearest neighbours in opposite pairs give
//!    the two grid directions; the grid grows outwards by predicting the next
//!    corner from the last step and taking the candidate there. Neighbouring
//!    corners must have opposite "checker polarity" (which diagonal is dark).
//! 3. Each corner is refined with the gradient-orthogonality method (the one
//!    behind OpenCV's cornerSubPix): every gradient around a true corner points
//!    across an edge that passes through it.
//!
//! The largest grid over all scales and seeds wins. Coordinates are pixel
//! centres at integer positions (add 0.5 for Tracker's image coordinates).

use rayon::prelude::*;
use std::collections::HashMap;

pub struct Corner {
    pub i: i32,
    pub j: i32,
    pub x: f64,
    pub y: f64,
}

struct Img {
    w: usize,
    h: usize,
    p: Vec<f32>,
}

impl Img {
    #[inline]
    fn at(&self, x: usize, y: usize) -> f32 {
        self.p[y * self.w + x]
    }

    /// bilinear, clamped to the image
    fn sample(&self, x: f64, y: f64) -> f32 {
        let xm = (self.w - 1) as f64;
        let ym = (self.h - 1) as f64;
        let x = x.clamp(0.0, xm);
        let y = y.clamp(0.0, ym);
        let x0 = (x.floor() as usize).min(self.w - 2);
        let y0 = (y.floor() as usize).min(self.h - 2);
        let fx = (x - x0 as f64) as f32;
        let fy = (y - y0 as f64) as f32;
        let a = self.at(x0, y0) * (1.0 - fx) + self.at(x0 + 1, y0) * fx;
        let b = self.at(x0, y0 + 1) * (1.0 - fx) + self.at(x0 + 1, y0 + 1) * fx;
        a * (1.0 - fy) + b * fy
    }
}

fn blur(src: &Img, sigma: f64) -> Img {
    let r = (3.0 * sigma).ceil().max(1.0) as isize;
    let k: Vec<f32> = (-r..=r).map(|i| (-(i * i) as f64 / (2.0 * sigma * sigma)).exp() as f32).collect();
    let ks: f32 = k.iter().sum();
    let k: Vec<f32> = k.iter().map(|v| v / ks).collect();
    let (w, h) = (src.w, src.h);
    let mut tmp = vec![0f32; w * h];
    tmp.par_chunks_mut(w).enumerate().for_each(|(y, row)| {
        let s = &src.p[y * w..(y + 1) * w];
        for (x, out) in row.iter_mut().enumerate() {
            let mut acc = 0f32;
            for (t, kv) in k.iter().enumerate() {
                let xx = (x as isize + t as isize - r).clamp(0, w as isize - 1) as usize;
                acc += kv * s[xx];
            }
            *out = acc;
        }
    });
    let mut dst = vec![0f32; w * h];
    dst.par_chunks_mut(w).enumerate().for_each(|(y, row)| {
        for (t, kv) in k.iter().enumerate() {
            let yy = (y as isize + t as isize - r).clamp(0, h as isize - 1) as usize;
            let s = &tmp[yy * w..(yy + 1) * w];
            for (o, v) in row.iter_mut().zip(s) {
                *o += kv * v;
            }
        }
    });
    Img { w, h, p: dst }
}

/// Saddle candidates: local maxima of -det(Hessian), sub-pixel by a quadratic fit.
fn candidates(b: &Img, sigma: f64) -> Vec<(f64, f64, f32)> {
    let (w, h) = (b.w, b.h);
    let mut r = vec![0f32; w * h];
    r.par_chunks_mut(w).enumerate().for_each(|(y, row)| {
        if y == 0 || y + 1 >= h {
            return;
        }
        for x in 1..w - 1 {
            let c = b.at(x, y);
            let ixx = b.at(x + 1, y) - 2.0 * c + b.at(x - 1, y);
            let iyy = b.at(x, y + 1) - 2.0 * c + b.at(x, y - 1);
            let ixy = 0.25 * (b.at(x + 1, y + 1) - b.at(x + 1, y - 1) - b.at(x - 1, y + 1) + b.at(x - 1, y - 1));
            row[x] = (ixy * ixy - ixx * iyy).max(0.0);
        }
    });
    let max = r.iter().cloned().fold(0f32, f32::max);
    if max <= 0.0 {
        return Vec::new();
    }
    let thr = max * 0.02;
    let k = ((sigma * 1.5).round() as usize).max(2);
    let mut out: Vec<(f64, f64, f32)> = (k..h.saturating_sub(k))
        .into_par_iter()
        .flat_map_iter(|y| {
            let r = &r;
            (k..w - k).filter_map(move |x| {
                let v = r[y * w + x];
                if v < thr {
                    return None;
                }
                for yy in y - k..=y + k {
                    for xx in x - k..=x + k {
                        let u = r[yy * w + xx];
                        if u > v || (u == v && (yy, xx) < (y, x)) {
                            return None;
                        }
                    }
                }
                let dx = {
                    let (a, c) = (r[y * w + x - 1], r[y * w + x + 1]);
                    let d = a - 2.0 * v + c;
                    if d < 0.0 { (0.5 * (a - c) / d) as f64 } else { 0.0 }
                };
                let dy = {
                    let (a, c) = (r[(y - 1) * w + x], r[(y + 1) * w + x]);
                    let d = a - 2.0 * v + c;
                    if d < 0.0 { (0.5 * (a - c) / d) as f64 } else { 0.0 }
                };
                Some((x as f64 + dx.clamp(-0.5, 0.5), y as f64 + dy.clamp(-0.5, 0.5), v))
            })
        })
        .collect();
    out.sort_by(|a, b| b.2.total_cmp(&a.2));
    out.truncate(4000);
    out
}

/// Which diagonal of the corner at c is darker (sign), given the grid steps u, v;
/// 0 unless it is a real X corner: opposite squares alike. (An L corner, where a
/// border square meets the white paper, has one diagonal pair unalike.)
fn polarity(b: &Img, c: (f64, f64), u: (f64, f64), v: (f64, f64)) -> f32 {
    let q = |a: f64, bb: f64| b.sample(c.0 + 0.4 * (a * u.0 + bb * v.0), c.1 + 0.4 * (a * u.1 + bb * v.1));
    let (pp, mm, pm, mp) = (q(1.0, 1.0), q(-1.0, -1.0), q(1.0, -1.0), q(-1.0, 1.0));
    let pol = pp + mm - pm - mp;
    if (pp - mm).abs() + (pm - mp).abs() > 0.5 * pol.abs() {
        return 0.0;
    }
    pol
}

fn sub(a: (f64, f64), b: (f64, f64)) -> (f64, f64) {
    (a.0 - b.0, a.1 - b.1)
}
fn add(a: (f64, f64), b: (f64, f64)) -> (f64, f64) {
    (a.0 + b.0, a.1 + b.1)
}
fn len(a: (f64, f64)) -> f64 {
    a.0.hypot(a.1)
}

struct Grid {
    nodes: HashMap<(i32, i32), (usize, (f64, f64), (f64, f64))>, // candidate, local u, local v
}

fn grow(b: &Img, cand: &[(f64, f64, f32)], seed: usize, min_contrast: f32) -> Option<Grid> {
    let c = (cand[seed].0, cand[seed].1);
    // nearest neighbours
    let mut nn: Vec<(f64, usize)> = cand
        .iter()
        .enumerate()
        .filter(|&(k, _)| k != seed)
        .map(|(k, p)| (len(sub((p.0, p.1), c)), k))
        .filter(|&(d, _)| d > 3.0)
        .collect();
    if nn.len() < 4 {
        return None;
    }
    nn.sort_by(|a, b| a.0.total_cmp(&b.0));
    nn.truncate(8);
    // opposite pairs a ~ c + u, b ~ c - u
    let mut pairs: Vec<(f64, f64)> = Vec::new();
    for x in 0..nn.len() {
        for y in x + 1..nn.len() {
            let a = sub((cand[nn[x].1].0, cand[nn[x].1].1), c);
            let bb = sub((cand[nn[y].1].0, cand[nn[y].1].1), c);
            let (la, lb) = (len(a), len(bb));
            if len(add(a, bb)) < 0.25 * la.min(lb) && (la - lb).abs() < 0.3 * la.min(lb) {
                pairs.push(((a.0 - bb.0) / 2.0, (a.1 - bb.1) / 2.0));
            }
        }
    }
    pairs.sort_by(|a, b| len(*a).total_cmp(&len(*b)));
    let u = *pairs.first()?;
    let v = *pairs.iter().skip(1).find(|p| {
        let cos = (u.0 * p.0 + u.1 * p.1) / (len(u) * len(**p));
        cos.abs() < 0.6 && len(**p) < 1.6 * len(u) && len(u) < 1.6 * len(**p)
    })?;
    let pol = polarity(b, c, u, v);
    if pol.abs() < min_contrast {
        return None;
    }

    let mut used = vec![false; cand.len()];
    let mut g = Grid { nodes: HashMap::new() };
    g.nodes.insert((0, 0), (seed, u, v));
    used[seed] = true;
    let mut queue = std::collections::VecDeque::new();
    queue.push_back((0i32, 0i32));
    while let Some((i, j)) = queue.pop_front() {
        let (k, lu, lv) = g.nodes[&(i, j)];
        let p = (cand[k].0, cand[k].1);
        let pp = polarity(b, p, lu, lv);
        for (di, dj) in [(1, 0), (-1, 0), (0, 1), (0, -1)] {
            let key = (i + di, j + dj);
            if g.nodes.contains_key(&key) {
                continue;
            }
            // step: continue the last step on this line if there is one, else the local basis
            let back = (i - di, j - dj);
            let step = match g.nodes.get(&back) {
                Some(&(kb, _, _)) => sub(p, (cand[kb].0, cand[kb].1)),
                None => (di as f64 * lu.0 + dj as f64 * lv.0, di as f64 * lu.1 + dj as f64 * lv.1),
            };
            let pred = add(p, step);
            let tol = 0.3 * len(step);
            let mut best: Option<(f64, usize)> = None;
            for (q, cq) in cand.iter().enumerate() {
                if used[q] {
                    continue;
                }
                let d = len(sub((cq.0, cq.1), pred));
                if d < tol && best.is_none_or(|b| d < b.0) {
                    best = Some((d, q));
                }
            }
            let Some((_, q)) = best else { continue };
            let qp = (cand[q].0, cand[q].1);
            let real = sub(qp, p);
            let (nu, nv) = if di != 0 {
                ((real.0 * di as f64, real.1 * di as f64), lv)
            } else {
                (lu, (real.0 * dj as f64, real.1 * dj as f64))
            };
            let qpol = polarity(b, qp, nu, nv);
            // neighbouring corners: opposite polarity, similar contrast
            if qpol * pp >= 0.0 || qpol.abs() < 0.4 * pp.abs() || qpol.abs() < min_contrast {
                continue;
            }
            used[q] = true;
            g.nodes.insert(key, (q, nu, nv));
            queue.push_back(key);
        }
    }
    Some(g)
}

/// Gradient-orthogonality refinement around (x, y) on image b.
fn refine(gx: &Img, gy: &Img, x: f64, y: f64, rad: i32) -> (f64, f64) {
    let (mut qx, mut qy) = (x, y);
    let s2 = (rad as f64 * 0.6).powi(2) * 2.0;
    for _ in 0..30 {
        let (mut a, mut bxy, mut c, mut bx, mut by) = (0f64, 0f64, 0f64, 0f64, 0f64);
        for dy in -rad..=rad {
            for dx in -rad..=rad {
                let px = qx + dx as f64;
                let py = qy + dy as f64;
                if px < 1.0 || py < 1.0 || px > (gx.w - 2) as f64 || py > (gx.h - 2) as f64 {
                    continue;
                }
                let wgt = (-((dx * dx + dy * dy) as f64) / s2).exp();
                let g0 = gx.sample(px, py) as f64;
                let g1 = gy.sample(px, py) as f64;
                let (xx, xy, yy) = (g0 * g0 * wgt, g0 * g1 * wgt, g1 * g1 * wgt);
                a += xx;
                bxy += xy;
                c += yy;
                bx += xx * px + xy * py;
                by += xy * px + yy * py;
            }
        }
        let det = a * c - bxy * bxy;
        if det.abs() < 1e-12 {
            break;
        }
        let nx = (c * bx - bxy * by) / det;
        let ny = (a * by - bxy * bx) / det;
        let moved = (nx - qx).hypot(ny - qy);
        if (nx - x).hypot(ny - y) > rad as f64 {
            return (x, y); // ran away: keep the detection
        }
        qx = nx;
        qy = ny;
        if moved < 0.002 {
            break;
        }
    }
    (qx, qy)
}

fn gradients(b: &Img) -> (Img, Img) {
    let (w, h) = (b.w, b.h);
    let mut gx = vec![0f32; w * h];
    let mut gy = vec![0f32; w * h];
    gx.par_chunks_mut(w).zip(gy.par_chunks_mut(w)).enumerate().for_each(|(y, (rx, ry))| {
        if y == 0 || y + 1 >= h {
            return;
        }
        for x in 1..w - 1 {
            rx[x] = 0.5 * (b.at(x + 1, y) - b.at(x - 1, y));
            ry[x] = 0.5 * (b.at(x, y + 1) - b.at(x, y - 1));
        }
    });
    (Img { w, h, p: gx }, Img { w, h, p: gy })
}

/// Finds the checkerboard in a grey image (0..255, row-major w x h).
pub fn find(gray: &[f32], w: usize, h: usize) -> Vec<Corner> {
    if w < 16 || h < 16 || gray.len() < w * h {
        return Vec::new();
    }
    let src = Img { w, h, p: gray[..w * h].to_vec() };
    let mut best: Option<(Vec<(i32, i32, f64, f64)>, f64)> = None;
    for &sigma in &[1.0f64, 1.6, 2.5, 4.0, 6.0] {
        let b = blur(&src, sigma);
        let cand = candidates(&b, sigma);
        if cand.len() < 9 {
            continue;
        }
        // contrast floor: a fraction of the strongest corner's polarity is checked per grid
        let mut taken = vec![false; cand.len()];
        let mut tries = 0;
        for seed in 0..cand.len() {
            if taken[seed] || tries >= 40 {
                continue;
            }
            tries += 1;
            let Some(g) = grow(&b, &cand, seed, 4.0) else { continue };
            for &(k, _, _) in g.nodes.values() {
                taken[k] = true;
            }
            if g.nodes.len() < 9 {
                continue;
            }
            let better = match &best {
                None => true,
                Some((pts, _)) => g.nodes.len() > pts.len(),
            };
            if better {
                // mean step, for the refinement window
                let step: f64 = g.nodes.values().map(|(_, u, v)| 0.5 * (len(*u) + len(*v))).sum::<f64>()
                    / g.nodes.len() as f64;
                let pts = g
                    .nodes
                    .iter()
                    .map(|(&(i, j), &(k, _, _))| (i, j, cand[k].0, cand[k].1))
                    .collect();
                best = Some((pts, step));
            }
        }
    }
    let Some((pts, step)) = best else { return Vec::new() };

    // refine on a light blur (keeps edges sharp, removes pixel noise)
    let sigma = (step / 12.0).clamp(0.7, 3.0);
    let b = blur(&src, sigma);
    let (gx, gy) = gradients(&b);
    let rad = ((step * 0.4).round() as i32).clamp(2, 15);
    let imin = pts.iter().map(|p| p.0).min().unwrap_or(0);
    let jmin = pts.iter().map(|p| p.1).min().unwrap_or(0);
    let mut out: Vec<Corner> = pts
        .par_iter()
        .map(|&(i, j, x, y)| {
            let (rx, ry) = refine(&gx, &gy, x, y, rad);
            Corner { i: i - imin, j: j - jmin, x: rx, y: ry }
        })
        .collect();
    out.sort_by_key(|c| (c.j, c.i));
    out
}
