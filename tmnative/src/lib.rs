//! Native template matcher for Tracker's AutoTracker.
//!
//! Replaces the hot loop of `TemplateMatcher.getMatchLocation`: for every
//! search point, sum the squared RGB difference between the template and the
//! target over the template's opaque pixels, then report the best (first
//! minimum) point, the sum of all diffs and how many points were tested.
//!
//! Results are bit-identical to the Java code: Java sums exact integers in a
//! double, so integer accumulation here gives the same values.
//!
//! Modes:
//!   0 = straight port of the Java loop (packed ARGB, branch per pixel)
//!   1 = planar i32 + branchless mask, auto-vectorized, single thread
//!   2 = mode 1 on all cores (rayon)
//!   3 = planar i16 (vpmaddwd-friendly), single thread
//!   4 = mode 3 on all cores (rayon)
//!   5 = row-batched: vectorized across neighbouring x-positions, single thread
//!   6 = mode 5 on all cores (rayon); line searches fall back to 3 / 4
//!   7 = auto: picks 10, 11 or 12 by estimated time (the default)
//!   8 = tiled: opaque pixels only, accumulators in registers, AVX2 when the
//!       CPU has it (runtime check), single thread
//!   9 = mode 8 on all cores (rayon)
//!  10 = SSD = |T|^2 - 2 T.I + |I|^2 with vpmaddwd and row prefix sums (AVX2),
//!       any template size (bands of <= 22000 pixels), single thread; line searches and CPUs without AVX2 fall back to 8
//!  11 = mode 10 on all cores (rayon); falls back to 9
//!  12 = FFT correlation for every position at once (rectangle searches with a
//!       full test image); exact by rounding, falls back to 11 otherwise
//!  99 = no search at all, to time the Java code around the kernel
//!
//! `refine` adds a sub-pixel step on top of the integer match: Gauss-Newton on
//! the masked SSD against a bicubic-interpolated target, which removes most of
//! the "pixel locking" bias of the 3-point parabola Tracker uses.

use jni_sys::*;
mod filedialog;
pub mod checker;
use rayon::prelude::*;
use std::ptr::null_mut;

/// Search result, matching the locals of the Java loop.
#[derive(Clone, Copy)]
struct Acc {
    min: u64,
    idx: i64,
    sum: u64,
    n: u64,
    nan: bool,
}

impl Acc {
    const EMPTY: Acc = Acc { min: u64::MAX, idx: -1, sum: 0, n: 0, nan: false };

    #[inline]
    fn push(mut self, i: usize, d: Option<u64>) -> Acc {
        self.n += 1;
        match d {
            // NaN in Java: poisons the sum, never wins the comparison
            None => self.nan = true,
            Some(d) => {
                self.sum += d;
                if d < self.min {
                    self.min = d;
                    self.idx = i as i64;
                }
            }
        }
        self
    }

    /// Combines two partial results; ties go to the lower index like the
    /// sequential Java loop.
    fn merge(a: Acc, b: Acc) -> Acc {
        let (min, idx) = if b.min < a.min || (b.min == a.min && b.idx >= 0 && (a.idx < 0 || b.idx < a.idx)) {
            (b.min, b.idx)
        } else {
            (a.min, a.idx)
        };
        Acc { min, idx, sum: a.sum + b.sum, n: a.n + b.n, nan: a.nan || b.nan }
    }
}

struct Geometry {
    w_test: usize,
    len: usize,
    wt: usize,
    ht: usize,
}

impl Geometry {
    /// Java's bounds test; `None` stands for the NaN it returns.
    #[inline]
    fn offset(&self, x: i32, y: i32) -> Option<usize> {
        let xyoff = y as i64 * self.w_test as i64 + x as i64;
        let pmax = xyoff + (self.ht as i64 - 1) * self.w_test as i64 + self.wt as i64;
        if xyoff < 0 || pmax >= self.len as i64 { None } else { Some(xyoff as usize) }
    }
}

// ---------------------------------------------------------------- mode 0

fn ssd_port(g: &Geometry, target: &[i32], tr: &[i32], tg: &[i32], tb: &[i32], transp: &[u8], x: i32, y: i32) -> Option<u64> {
    let xyoff = g.offset(x, y)?;
    let mut diff: u64 = 0;
    let mut tpt = 0;
    let mut ti = xyoff;
    for _ in 0..g.ht {
        for _ in 0..g.wt {
            if transp[tpt] == 0 {
                let p = target[ti];
                let dr = tr[tpt] - ((p >> 16) & 0xff);
                let dg = tg[tpt] - ((p >> 8) & 0xff);
                let db = tb[tpt] - (p & 0xff);
                diff += (dr * dr + dg * dg + db * db) as u64;
            }
            tpt += 1;
            ti += 1;
        }
        ti += g.w_test - g.wt;
    }
    Some(diff)
}

// ---------------------------------------------------------------- planar

/// Target or template split into colour planes; `m` is 0 or -1 (all bits).
struct Planes<T> {
    r: Vec<T>,
    g: Vec<T>,
    b: Vec<T>,
    m: Vec<T>,
}

fn split_target<T: From<u8>>(target: &[i32]) -> Planes<T> {
    let ch = |s: u32| target.iter().map(|&p| T::from((p >> s) as u8)).collect();
    Planes { r: ch(16), g: ch(8), b: ch(0), m: Vec::new() }
}

fn split_template_i32(tr: &[i32], tg: &[i32], tb: &[i32], transp: &[u8]) -> Planes<i32> {
    Planes {
        r: tr.to_vec(),
        g: tg.to_vec(),
        b: tb.to_vec(),
        m: transp.iter().map(|&t| if t == 0 { -1 } else { 0 }).collect(),
    }
}

fn split_template_i16(tr: &[i32], tg: &[i32], tb: &[i32], transp: &[u8]) -> Planes<i16> {
    let c = |v: &[i32]| v.iter().map(|&x| x as i16).collect();
    Planes {
        r: c(tr),
        g: c(tg),
        b: c(tb),
        m: transp.iter().map(|&t| if t == 0 { -1 } else { 0 }).collect(),
    }
}

#[inline]
fn ssd_i32(g: &Geometry, t: &Planes<i32>, p: &Planes<i32>, x: i32, y: i32) -> Option<u64> {
    let xyoff = g.offset(x, y)?;
    let mut acc: u64 = 0;
    for j in 0..g.ht {
        let o = xyoff + j * g.w_test;
        let k = j * g.wt;
        let (pr, pg, pb) = (&p.r[o..o + g.wt], &p.g[o..o + g.wt], &p.b[o..o + g.wt]);
        let (tr, tg, tb, tm) = (&t.r[k..k + g.wt], &t.g[k..k + g.wt], &t.b[k..k + g.wt], &t.m[k..k + g.wt]);
        let mut s: i32 = 0;
        for i in 0..g.wt {
            let dr = tr[i] - pr[i];
            let dg = tg[i] - pg[i];
            let db = tb[i] - pb[i];
            s += (dr * dr + dg * dg + db * db) & tm[i];
        }
        acc += s as u64;
    }
    Some(acc)
}

#[inline]
fn ssd_i16(g: &Geometry, t: &Planes<i16>, p: &Planes<i16>, x: i32, y: i32) -> Option<u64> {
    let xyoff = g.offset(x, y)?;
    let mut acc: u64 = 0;
    for j in 0..g.ht {
        let o = xyoff + j * g.w_test;
        let k = j * g.wt;
        let (pr, pg, pb) = (&p.r[o..o + g.wt], &p.g[o..o + g.wt], &p.b[o..o + g.wt]);
        let (tr, tg, tb, tm) = (&t.r[k..k + g.wt], &t.g[k..k + g.wt], &t.b[k..k + g.wt], &t.m[k..k + g.wt]);
        let mut s: i32 = 0;
        for i in 0..g.wt {
            let dr = ((tr[i] - pr[i]) & tm[i]) as i32;
            let dg = ((tg[i] - pg[i]) & tm[i]) as i32;
            let db = ((tb[i] - pb[i]) & tm[i]) as i32;
            s += dr * dr + dg * dg + db * db;
        }
        acc += s as u64;
    }
    Some(acc)
}

// ---------------------------------------------------------------- row-batched

/// One row `y` of a rectangle search, all `sw` x-positions at once: for each
/// opaque template pixel, add its squared diff to every x-position. The inner
/// loop runs over contiguous target pixels and vectorizes fully, and
/// transparent template pixels are skipped outright.
fn ssd_row(g: &Geometry, t: &Planes<i16>, p: &Planes<i16>, y: usize, sw: usize, acc: &mut [u32]) {
    acc.fill(0);
    for j in 0..g.ht {
        let row = (y + j) * g.w_test;
        for i in 0..g.wt {
            let k = j * g.wt + i;
            if t.m[k] == 0 {
                continue;
            }
            let (tr, tg, tb) = (t.r[k], t.g[k], t.b[k]);
            let o = row + i;
            let (pr, pg, pb) = (&p.r[o..o + sw], &p.g[o..o + sw], &p.b[o..o + sw]);
            for x in 0..sw {
                // |d| <= 255, so d*d <= 65025 is exact in u16
                let dr = (tr - pr[x]) as u16;
                let dg = (tg - pg[x]) as u16;
                let db = (tb - pb[x]) as u16;
                acc[x] += dr.wrapping_mul(dr) as u32 + dg.wrapping_mul(dg) as u32 + db.wrapping_mul(db) as u32;
            }
        }
    }
}

fn search_rows(g: &Geometry, t: &Planes<i16>, p: &Planes<i16>, sw: usize, sh: usize, parallel: bool) -> Acc {
    let row_acc = |y: usize, buf: &mut Vec<u32>| {
        buf.resize(sw, 0);
        ssd_row(g, t, p, y, sw, buf);
        // x ascending = index ascending for this y, so push keeps the first minimum
        buf.iter().enumerate().fold(Acc::EMPTY, |a, (x, &d)| a.push(x * sh + y, Some(d as u64)))
    };
    if parallel {
        (0..sh)
            .into_par_iter()
            .map_init(Vec::new, |buf, y| row_acc(y, buf))
            .reduce(|| Acc::EMPTY, Acc::merge)
    } else {
        let mut buf = Vec::new();
        (0..sh).map(|y| row_acc(y, &mut buf)).fold(Acc::EMPTY, Acc::merge)
    }
}

// ---------------------------------------------------------------- tiled (AVX2)

/// Opaque template pixels only, as (offset into the test image, r, g, b), so
/// the hot loop has no mask test and transparent pixels cost nothing.
struct Opaque {
    off: Vec<usize>,
    r: Vec<i16>,
    g: Vec<i16>,
    b: Vec<i16>,
}

fn opaque(g: &Geometry, tr: &[i32], tg: &[i32], tb: &[i32], transp: &[u8]) -> Opaque {
    let mut o = Opaque { off: Vec::new(), r: Vec::new(), g: Vec::new(), b: Vec::new() };
    for j in 0..g.ht {
        for i in 0..g.wt {
            let k = j * g.wt + i;
            if transp[k] == 0 {
                o.off.push(j * g.w_test + i);
                o.r.push(tr[k] as i16);
                o.g.push(tg[k] as i16);
                o.b.push(tb[k] as i16);
            }
        }
    }
    o
}

/// Diffs of `N` neighbouring x-positions starting at test offset `base`. The
/// accumulators stay in registers for the whole template instead of being
/// loaded and stored once per template pixel as in `ssd_row`.
#[inline(always)]
fn tile<const N: usize>(o: &Opaque, p: &Planes<i16>, base: usize) -> [u32; N] {
    let mut acc = [0u32; N];
    for k in 0..o.off.len() {
        let s = base + o.off[k];
        let (tr, tg, tb) = (o.r[k], o.g[k], o.b[k]);
        let pr: &[i16; N] = p.r[s..s + N].try_into().unwrap();
        let pg: &[i16; N] = p.g[s..s + N].try_into().unwrap();
        let pb: &[i16; N] = p.b[s..s + N].try_into().unwrap();
        for x in 0..N {
            // |d| <= 255, so d*d <= 65025 is exact in u16
            let dr = (tr - pr[x]) as u16;
            let dg = (tg - pg[x]) as u16;
            let db = (tb - pb[x]) as u16;
            acc[x] += dr.wrapping_mul(dr) as u32 + dg.wrapping_mul(dg) as u32 + db.wrapping_mul(db) as u32;
        }
    }
    acc
}

const TILE: usize = 32;

#[inline(always)]
fn row_body(o: &Opaque, p: &Planes<i16>, w_test: usize, y: usize, out: &mut [u32]) {
    let sw = out.len();
    let base = y * w_test;
    let mut x = 0;
    while x + TILE <= sw {
        out[x..x + TILE].copy_from_slice(&tile::<TILE>(o, p, base + x));
        x += TILE;
    }
    while x + 8 <= sw {
        out[x..x + 8].copy_from_slice(&tile::<8>(o, p, base + x));
        x += 8;
    }
    while x < sw {
        out[x] = tile::<1>(o, p, base + x)[0];
        x += 1;
    }
}

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
unsafe fn row_avx2(o: &Opaque, p: &Planes<i16>, w_test: usize, y: usize, out: &mut [u32]) {
    row_body(o, p, w_test, y, out)
}

fn row_plain(o: &Opaque, p: &Planes<i16>, w_test: usize, y: usize, out: &mut [u32]) {
    row_body(o, p, w_test, y, out)
}

type RowFn = fn(&Opaque, &Planes<i16>, usize, usize, &mut [u32]);

/// The row kernel for this CPU: AVX2 when available (any x86 CPU since ~2013),
/// otherwise the SSE2 baseline. Both give identical integer results.
fn row_kernel() -> RowFn {
    #[cfg(target_arch = "x86_64")]
    if std::is_x86_feature_detected!("avx2") {
        return |o, p, w, y, out| unsafe { row_avx2(o, p, w, y, out) };
    }
    row_plain
}

fn search_tiled(g: &Geometry, o: &Opaque, p: &Planes<i16>, sw: usize, sh: usize, parallel: bool) -> Acc {
    let kernel = row_kernel();
    search_by_rows(sw, sh, parallel, &|y, out| kernel(o, p, g.w_test, y, out))
}

/// Runs a row kernel (all x-positions of row y into `out`) over the rectangle
/// and reduces to the first minimum in Java's x-major index order.
fn search_by_rows<T>(sw: usize, sh: usize, parallel: bool, kernel: &(dyn Fn(usize, &mut [T]) + Sync)) -> Acc
where
    T: Copy + Default + Into<u64> + Send,
{
    let row_acc = |y: usize, buf: &mut Vec<T>| {
        buf.resize(sw, T::default());
        kernel(y, buf);
        // x ascending = index ascending for this y, so push keeps the first minimum
        buf.iter().enumerate().fold(Acc::EMPTY, |a, (x, &d)| a.push(x * sh + y, Some(d.into())))
    };
    if parallel {
        (0..sh)
            .into_par_iter()
            .map_init(Vec::new, |buf, y| row_acc(y, buf))
            .reduce(|| Acc::EMPTY, Acc::merge)
    } else {
        let mut buf = Vec::new();
        (0..sh).map(|y| row_acc(y, &mut buf)).fold(Acc::EMPTY, Acc::merge)
    }
}

// ---------------------------------------------------------------- madd (AVX2)

/// Exact integer identity per position x, over the opaque template pixels k:
///
///   SSD(x) = sum |T_k|^2  -  2 sum T_k . I(x + k)  +  sum |I(x + k)|^2
///
/// The cross term is one `vpmaddwd` per channel pair (r,g) and one for b, and
/// the last term comes from row prefix sums over the opaque runs of each
/// template row, so it costs O(runs) instead of O(pixels). Everything wraps
/// mod 2^32; the true SSD fits in u32 (checked by the caller), so the wrapped
/// result is exact and identical to the other modes.
#[cfg(target_arch = "x86_64")]
mod madd {
    use super::Geometry;
    use std::arch::x86_64::*;

    /// Padding so that 8-wide loads past the last valid position stay in bounds.
    const PAD: usize = 64;
    /// Most opaque pixels one band may have so its SSD fits in u32
    /// (22017 * 195075 < 2^32). Bigger templates are split into row bands
    /// whose u32 results are added in u64.
    pub const PART_MAX: usize = 22_000;

    /// A band of template rows.
    struct Part {
        /// opaque pixels: offset into the test image, weights (-2r | -2g << 16) and -2b
        off: Vec<usize>,
        wrg: Vec<i32>,
        wb: Vec<i32>,
        /// opaque runs per template row as (offset of row start in `pre`, a, b)
        runs: Vec<(usize, usize, usize)>,
        /// sum |T|^2 over the band's opaque pixels
        ct: u32,
    }

    pub struct Prep {
        w_test: usize,
        /// target (r | g << 16) and b per pixel, padded
        rg: Vec<i32>,
        b: Vec<i32>,
        /// row prefix sums of |I|^2, rows of w_test + 1, padded
        pre: Vec<u32>,
        parts: Vec<Part>,
    }

    pub fn prepare(g: &Geometry, target: &[i32], tr: &[i32], tg: &[i32], tb: &[i32], transp: &[u8]) -> Prep {
        let w = g.w_test;
        let rows = g.len / w;
        let mut rg = Vec::with_capacity(g.len + PAD);
        let mut b = Vec::with_capacity(g.len + PAD);
        let mut pre = Vec::with_capacity(rows * (w + 1) + PAD);
        for r in 0..rows {
            let mut s = 0u32;
            pre.push(0);
            for &p in &target[r * w..r * w + w] {
                let (pr, pg, pb) = ((p >> 16) & 0xff, (p >> 8) & 0xff, p & 0xff);
                rg.push(pr | (pg << 16));
                b.push(pb);
                s = s.wrapping_add((pr * pr + pg * pg + pb * pb) as u32);
                pre.push(s);
            }
        }
        rg.resize(g.len + PAD, 0);
        b.resize(g.len + PAD, 0);
        pre.resize(rows * (w + 1) + PAD, 0);
        let new_part = || Part { off: vec![], wrg: vec![], wb: vec![], runs: vec![], ct: 0 };
        let mut parts = vec![new_part()];
        for j in 0..g.ht {
            let row_opaque = (0..g.wt).filter(|&i| transp[j * g.wt + i] == 0).count();
            if parts.last().unwrap().off.len() + row_opaque > PART_MAX {
                parts.push(new_part());
            }
            let part = parts.last_mut().unwrap();
            let mut start = None;
            for i in 0..=g.wt {
                let k = j * g.wt + i;
                if i < g.wt && transp[k] == 0 {
                    part.off.push(j * w + i);
                    part.wrg.push(((-2 * tr[k]) & 0xffff) | ((-2 * tg[k]) << 16));
                    part.wb.push((-2 * tb[k]) & 0xffff);
                    part.ct = part.ct.wrapping_add((tr[k] * tr[k] + tg[k] * tg[k] + tb[k] * tb[k]) as u32);
                    start.get_or_insert(i);
                } else if let Some(a) = start.take() {
                    part.runs.push((j * (w + 1), a, i));
                }
            }
        }
        Prep { w_test: w, rg, b, pre, parts }
    }

    /// `V` vectors of 8 positions starting at x in row y, for one band.
    #[inline]
    #[target_feature(enable = "avx2")]
    unsafe fn tile<const V: usize>(p: &Prep, part: &Part, y: usize, x: usize) -> [__m256i; V] {
        unsafe {
            let mut acc = [_mm256_set1_epi32(part.ct as i32); V];
            let base = y * p.w_test + x;
            let (rg, b) = (p.rg.as_ptr(), p.b.as_ptr());
            for k in 0..part.off.len() {
                let s = base + *part.off.get_unchecked(k);
                let wrg = _mm256_set1_epi32(*part.wrg.get_unchecked(k));
                let wb = _mm256_set1_epi32(*part.wb.get_unchecked(k));
                for v in 0..V {
                    let a = _mm256_loadu_si256(rg.add(s + 8 * v) as *const __m256i);
                    let c = _mm256_loadu_si256(b.add(s + 8 * v) as *const __m256i);
                    let t = _mm256_add_epi32(_mm256_madd_epi16(a, wrg), _mm256_madd_epi16(c, wb));
                    acc[v] = _mm256_add_epi32(acc[v], t);
                }
            }
            let pre = p.pre.as_ptr();
            let row0 = y * (p.w_test + 1) + x;
            for &(ro, a, bb) in &part.runs {
                let q = row0 + ro;
                for v in 0..V {
                    let hi = _mm256_loadu_si256(pre.add(q + bb + 8 * v) as *const __m256i);
                    let lo = _mm256_loadu_si256(pre.add(q + a + 8 * v) as *const __m256i);
                    acc[v] = _mm256_add_epi32(acc[v], _mm256_sub_epi32(hi, lo));
                }
            }
            acc
        }
    }

    /// `V * 8` positions from x into `tot`, summed over all bands in u64.
    #[inline]
    #[target_feature(enable = "avx2")]
    unsafe fn block<const V: usize>(p: &Prep, y: usize, x: usize, tot: &mut [u64]) {
        unsafe {
            tot.fill(0);
            let mut tmp = [[0u32; 8]; V];
            for part in &p.parts {
                let a = tile::<V>(p, part, y, x);
                for v in 0..V {
                    _mm256_storeu_si256(tmp[v].as_mut_ptr() as *mut __m256i, a[v]);
                }
                for (t, &d) in tot.iter_mut().zip(tmp.iter().flatten()) {
                    *t += d as u64;
                }
            }
        }
    }

    /// All `out.len()` positions of row y.
    #[target_feature(enable = "avx2")]
    pub unsafe fn row(p: &Prep, y: usize, out: &mut [u64]) {
        unsafe {
            let sw = out.len();
            let mut x = 0;
            while x + 32 <= sw {
                block::<4>(p, y, x, &mut out[x..x + 32]);
                x += 32;
            }
            while x < sw {
                // last partial vector reads into the padding; only valid lanes are kept
                let mut tmp = [0u64; 8];
                block::<1>(p, y, x, &mut tmp);
                let n = (sw - x).min(8);
                out[x..x + n].copy_from_slice(&tmp[..n]);
                x += 8;
            }
        }
    }
}

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2")]
unsafe fn ssd_i16_avx2(g: &Geometry, t: &Planes<i16>, p: &Planes<i16>, x: i32, y: i32) -> Option<u64> {
    ssd_i16(g, t, p, x, y)
}

type PointFn = fn(&Geometry, &Planes<i16>, &Planes<i16>, i32, i32) -> Option<u64>;

/// Per-point SSD for line searches: the masked i16 kernel, AVX2 when available.
fn point_kernel() -> PointFn {
    #[cfg(target_arch = "x86_64")]
    if std::is_x86_feature_detected!("avx2") {
        return |g, t, p, x, y| unsafe { ssd_i16_avx2(g, t, p, x, y) };
    }
    ssd_i16
}

// ---------------------------------------------------------------- sub-pixel refinement

/// Keys cubic convolution (a = -0.5) weights for taps -1, 0, 1, 2 at fraction
/// `f`, and their derivatives with respect to `f`.
#[inline]
fn cubic(f: f64) -> ([f64; 4], [f64; 4]) {
    let (f2, f3) = (f * f, f * f * f);
    (
        [
            0.5 * (-f3 + 2.0 * f2 - f),
            0.5 * (3.0 * f3 - 5.0 * f2 + 2.0),
            0.5 * (-3.0 * f3 + 4.0 * f2 + f),
            0.5 * (f3 - f2),
        ],
        [
            0.5 * (-3.0 * f2 + 4.0 * f - 1.0),
            0.5 * (9.0 * f2 - 10.0 * f),
            0.5 * (-9.0 * f2 + 8.0 * f + 1.0),
            0.5 * (3.0 * f2 - 2.0 * f),
        ],
    )
}

/// Aitken extrapolation of the Gauss-Newton steps (environment TM_NO_ACCEL=1 disables, for comparison).
static ACCEL_ON: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
#[allow(non_snake_case)]
fn ACCEL_get() -> bool {
    *ACCEL_ON.get_or_init(|| std::env::var_os("TM_NO_ACCEL").is_none())
}

/// How far the refined offset may move from the integer match, in pixels.
const MAX_SHIFT: f64 = 1.5;
/// Extra pixels needed around the template for the cubic taps at MAX_SHIFT.
const MARGIN: i64 = 4;

/// Row-band parallel refine; environment TM_REFINE_PAR=1 turns it on (for comparisons).
fn refine_par() -> bool {
    static P: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
    // off by default: measured slower (thread wake-ups cost more than the split saves)
    *P.get_or_init(|| std::env::var("TM_REFINE_PAR").map(|v| v == "1").unwrap_or(false))
}

/// Lanes of the accumulators in `normal_eq`: independent partial sums let the
/// compiler vectorise the loops (float sums are not reassociated otherwise).
const LANES: usize = 8;

/// Template data for `normal_eq`: per channel, the template values as f64 in
/// rows padded to a multiple of LANES, and the opacity weight (1 or 0).
struct RefTpl {
    wp: usize,
    t: [Vec<f64>; 3],
    m: Vec<f64>,
}

fn ref_tpl(tpl: &[&[i32]; 3], transp: &[u8], wt: usize, ht: usize) -> RefTpl {
    let wp = wt.div_ceil(LANES) * LANES;
    let mut t = [vec![0f64; wp * ht], vec![0f64; wp * ht], vec![0f64; wp * ht]];
    let mut m = vec![0f64; wp * ht];
    for j in 0..ht {
        for i in 0..wt {
            let k = j * wt + i;
            if transp[k] == 0 {
                m[j * wp + i] = 1.0;
                for c in 0..3 {
                    t[c][j * wp + i] = tpl[c][k] as f64;
                }
            }
        }
    }
    RefTpl { wp, t, m }
}

/// Gauss-Newton normal equations [hxx, hxy, hyy, bx, by] at the offset whose
/// integer part is (ix, iy) and cubic weights wx/wy (derivatives dx/dy).
/// The fraction is the same for every pixel, so the bicubic is separable:
/// a horizontal pass (value and d/dx) over all needed rows, then a vertical
/// pass along whole rows. `win` rows have stride `ws` >= padded width + 2 * MARGIN.
#[allow(clippy::too_many_arguments)]
fn normal_eq(win: &[Vec<f64>; 3], ws: usize, rt: &RefTpl, ht: usize, ix: i64, iy: i64,
             wx: &[f64; 4], dx: &[f64; 4], wy: &[f64; 4], dy: &[f64; 4]) -> [f64; 5] {
    let add = |mut a: [f64; 5], p: [f64; 5]| {
        for (x, y) in a.iter_mut().zip(p) {
            *x += y;
        }
        a
    };
    #[cfg(target_arch = "x86_64")]
    if is_x86_feature_detected!("avx2") && is_x86_feature_detected!("fma") {
        // big templates: channels x row bands on the rayon pool (it is usually busy
        // anyway right after the search); partial sums added in a fixed order, so the
        // result does not depend on the number of threads
        let bands = if refine_par() && rt.wp * ht >= 6000 { 4 } else { 1 };
        let task = |t: usize| -> [f64; 5] {
            let (c, b) = (t / bands, t % bands);
            let (j0, j1) = (ht * b / bands, ht * (b + 1) / bands);
            unsafe { normal_eq_avx2(win, ws, rt, j0, j1, ix, iy, wx, dx, wy, dy, c) }
        };
        let parts: Vec<[f64; 5]> = if bands > 1 {
            (0..3 * bands).into_par_iter().map(task).collect()
        } else {
            (0..3).map(task).collect()
        };
        return parts.into_iter().fold([0f64; 5], add);
    }
    (0..3).map(|c| normal_eq_body(win, ws, rt, ht, ix, iy, wx, dx, wy, dy, c)).fold([0f64; 5], add)
}

#[cfg(target_arch = "x86_64")]
#[target_feature(enable = "avx2,fma")]
#[allow(clippy::too_many_arguments)]
unsafe fn normal_eq_avx2(win: &[Vec<f64>; 3], ws: usize, rt: &RefTpl, j0: usize, j1: usize, ix: i64, iy: i64,
                         wx: &[f64; 4], dx: &[f64; 4], wy: &[f64; 4], dy: &[f64; 4], c: usize) -> [f64; 5] {
    use std::arch::x86_64::*;
    unsafe {
        let wp = rt.wp; // multiple of LANES (8) = 2 vectors
        let rows = j1 - j0 + 3; // template rows j0..j1 need window rows j0..j1+3
        let r0 = (MARGIN + iy - 1) as usize;
        let c0 = (MARGIN + ix - 1) as usize;
        let mut hv = vec![0f64; rows * wp];
        let mut hd = vec![0f64; rows * wp];
        let s = _mm256_set1_pd;
        let (x0, x1, x2, x3) = (s(wx[0]), s(wx[1]), s(wx[2]), s(wx[3]));
        let (e0, e1, e2, e3) = (s(dx[0]), s(dx[1]), s(dx[2]), s(dx[3]));
        let (y0, y1, y2, y3) = (s(wy[0]), s(wy[1]), s(wy[2]), s(wy[3]));
        let (f0, f1, f2, f3) = (s(dy[0]), s(dy[1]), s(dy[2]), s(dy[3]));
        let z = _mm256_setzero_pd();
        let mut acc = [z; 10];
        let ld = |p: *const f64| _mm256_loadu_pd(p);
        {
            let w = win[c].as_ptr();
            for r in 0..rows {
                let a = w.add((r0 + j0 + r) * ws + c0);
                let (ov, od) = (hv.as_mut_ptr().add(r * wp), hd.as_mut_ptr().add(r * wp));
                for i in (0..wp).step_by(4) {
                    let (q0, q1, q2, q3) = (ld(a.add(i)), ld(a.add(i + 1)), ld(a.add(i + 2)), ld(a.add(i + 3)));
                    let v = _mm256_fmadd_pd(x3, q3, _mm256_fmadd_pd(x2, q2, _mm256_fmadd_pd(x1, q1, _mm256_mul_pd(x0, q0))));
                    let d = _mm256_fmadd_pd(e3, q3, _mm256_fmadd_pd(e2, q2, _mm256_fmadd_pd(e1, q1, _mm256_mul_pd(e0, q0))));
                    _mm256_storeu_pd(ov.add(i), v);
                    _mm256_storeu_pd(od.add(i), d);
                }
            }
            let (tc, mk) = (rt.t[c].as_ptr(), rt.m.as_ptr());
            for j in j0..j1 {
                let h = hv.as_ptr().add((j - j0) * wp);
                let d = hd.as_ptr().add((j - j0) * wp);
                for i0 in (0..wp).step_by(8) {
                    for u in 0..2 {
                        let i = i0 + 4 * u;
                        let (h0, h1, h2, h3) = (ld(h.add(i)), ld(h.add(i + wp)), ld(h.add(i + 2 * wp)), ld(h.add(i + 3 * wp)));
                        let (d0, d1, d2, d3) = (ld(d.add(i)), ld(d.add(i + wp)), ld(d.add(i + 2 * wp)), ld(d.add(i + 3 * wp)));
                        let m = ld(mk.add(j * wp + i));
                        let v = _mm256_fmadd_pd(y3, h3, _mm256_fmadd_pd(y2, h2, _mm256_fmadd_pd(y1, h1, _mm256_mul_pd(y0, h0))));
                        let gx = _mm256_mul_pd(m, _mm256_fmadd_pd(y3, d3, _mm256_fmadd_pd(y2, d2, _mm256_fmadd_pd(y1, d1, _mm256_mul_pd(y0, d0)))));
                        let gy = _mm256_mul_pd(m, _mm256_fmadd_pd(f3, h3, _mm256_fmadd_pd(f2, h2, _mm256_fmadd_pd(f1, h1, _mm256_mul_pd(f0, h0)))));
                        let e = _mm256_sub_pd(ld(tc.add(j * wp + i)), v);
                        let k = 5 * u;
                        acc[k] = _mm256_fmadd_pd(gx, gx, acc[k]);
                        acc[k + 1] = _mm256_fmadd_pd(gx, gy, acc[k + 1]);
                        acc[k + 2] = _mm256_fmadd_pd(gy, gy, acc[k + 2]);
                        acc[k + 3] = _mm256_fmadd_pd(gx, e, acc[k + 3]);
                        acc[k + 4] = _mm256_fmadd_pd(gy, e, acc[k + 4]);
                    }
                }
            }
        }
        let mut out = [0f64; 5];
        for (k, o) in out.iter_mut().enumerate() {
            let mut t = [0f64; 4];
            _mm256_storeu_pd(t.as_mut_ptr(), _mm256_add_pd(acc[k], acc[k + 5]));
            *o = t.iter().sum();
        }
        out
    }
}

#[inline(always)]
#[allow(clippy::too_many_arguments)]
fn normal_eq_body(win: &[Vec<f64>; 3], ws: usize, rt: &RefTpl, ht: usize, ix: i64, iy: i64,
                  wx: &[f64; 4], dx: &[f64; 4], wy: &[f64; 4], dy: &[f64; 4], c: usize) -> [f64; 5] {
    let wp = rt.wp;
    let rows = ht + 3;
    let r0 = (MARGIN + iy - 1) as usize;
    let c0 = (MARGIN + ix - 1) as usize;
    let mut acc = [[0f64; LANES]; 5];
    let mut hv = vec![0f64; rows * wp];
    let mut hd = vec![0f64; rows * wp];
    {
        for r in 0..rows {
            let a = &win[c][(r0 + r) * ws + c0..(r0 + r) * ws + c0 + wp + 3];
            let (a0, a1, a2, a3) = (&a[0..wp], &a[1..wp + 1], &a[2..wp + 2], &a[3..wp + 3]);
            let ov = &mut hv[r * wp..(r + 1) * wp];
            for i in 0..wp {
                ov[i] = wx[0] * a0[i] + wx[1] * a1[i] + wx[2] * a2[i] + wx[3] * a3[i];
            }
            let od = &mut hd[r * wp..(r + 1) * wp];
            for i in 0..wp {
                od[i] = dx[0] * a0[i] + dx[1] * a1[i] + dx[2] * a2[i] + dx[3] * a3[i];
            }
        }
        let tc = &rt.t[c];
        for j in 0..ht {
            let h = |m: usize| &hv[(j + m) * wp..(j + m + 1) * wp];
            let d = |m: usize| &hd[(j + m) * wp..(j + m + 1) * wp];
            let (h0, h1, h2, h3) = (h(0), h(1), h(2), h(3));
            let (d0, d1, d2, d3) = (d(0), d(1), d(2), d(3));
            let (tr, mr) = (&tc[j * wp..(j + 1) * wp], &rt.m[j * wp..(j + 1) * wp]);
            for i0 in (0..wp).step_by(LANES) {
                for l in 0..LANES {
                    let i = i0 + l;
                    let v = wy[0] * h0[i] + wy[1] * h1[i] + wy[2] * h2[i] + wy[3] * h3[i];
                    let gx = (wy[0] * d0[i] + wy[1] * d1[i] + wy[2] * d2[i] + wy[3] * d3[i]) * mr[i];
                    let gy = (dy[0] * h0[i] + dy[1] * h1[i] + dy[2] * h2[i] + dy[3] * h3[i]) * mr[i];
                    let e = tr[i] - v;
                    acc[0][l] += gx * gx;
                    acc[1][l] += gx * gy;
                    acc[2][l] += gy * gy;
                    acc[3][l] += gx * e;
                    acc[4][l] += gy * e;
                }
            }
        }
    }
    acc.map(|a| a.iter().sum())
}

/// Sub-pixel position of the template near the integer match (xm, ym), found
/// by minimising the masked SSD against a bicubic-interpolated target with
/// Gauss-Newton, starting from (s0x, s0y). With a direction (ux, uy) the
/// offset is restricted to that line. `None` when the window leaves the test
/// image, the fit is degenerate or it wanders off; the caller then keeps the
/// parabolic estimate.
#[allow(clippy::too_many_arguments)]
fn refine(target: &[i32], w_test: usize, tr: &[i32], tg: &[i32], tb: &[i32], transp: &[u8],
          wt: usize, ht: usize, xm: i32, ym: i32, s0: (f64, f64), dir: Option<(f64, f64)>) -> Option<(f64, f64)> {
    let h_test = target.len() / w_test.max(1);
    let (x0, y0) = (xm as i64 - MARGIN, ym as i64 - MARGIN);
    let (ww, wh) = (wt + 2 * MARGIN as usize, ht + 2 * MARGIN as usize);
    if x0 < 0 || y0 < 0 || x0 as usize + ww > w_test || y0 as usize + wh > h_test {
        return None;
    }
    let rt = ref_tpl(&[tr, tg, tb], transp, wt, ht);
    // planar f64 window around the match; rows padded with zeros to stride ws so
    // the horizontal pass can run over the padded template width
    let ws = rt.wp + 2 * MARGIN as usize;
    let mut win = [vec![0f64; ws * wh], vec![0f64; ws * wh], vec![0f64; ws * wh]];
    for j in 0..wh {
        for i in 0..ww {
            let p = target[(y0 as usize + j) * w_test + x0 as usize + i];
            win[0][j * ws + i] = ((p >> 16) & 0xff) as f64;
            win[1][j * ws + i] = ((p >> 8) & 0xff) as f64;
            win[2][j * ws + i] = (p & 0xff) as f64;
        }
    }
    let clamp = |v: f64| if v.is_finite() { v.clamp(-1.0, 1.0) } else { 0.0 };
    let (mut sx, mut sy) = match dir {
        // start on the line, at the parabola's distance along it
        Some((ux, uy)) => {
            let t = clamp(s0.0 * ux + s0.1 * uy);
            (t * ux, t * uy)
        }
        None => (clamp(s0.0), clamp(s0.1)),
    };
    let mut prev: Option<(f64, f64)> = None;
    for _ in 0..30 {
        let (fx0, fy0) = (sx.floor(), sy.floor());
        let (ix, iy) = (fx0 as i64, fy0 as i64);
        let (wx, dx) = cubic(sx - fx0);
        let (wy, dy) = cubic(sy - fy0);
        let [hxx, hxy, hyy, bx, by] = normal_eq(&win, ws, &rt, ht, ix, iy, &wx, &dx, &wy, &dy);
        let (ddx, ddy) = match dir {
            Some((ux, uy)) => {
                let h = ux * ux * hxx + 2.0 * ux * uy * hxy + uy * uy * hyy;
                if !(h > 1e-9) {
                    return None;
                }
                let t = (ux * bx + uy * by) / h;
                (t * ux, t * uy)
            }
            None => {
                let det = hxx * hyy - hxy * hxy;
                if !(det > 1e-9 * (hxx * hyy).max(1e-300)) {
                    return None;
                }
                ((hyy * bx - hxy * by) / det, (hxx * by - hxy * bx) / det)
            }
        };
        // damp big steps so a poor start cannot jump over the minimum
        let step = (ddx * ddx + ddy * ddy).sqrt();
        let scale = if step > 0.5 { 0.5 / step } else { 1.0 };
        let (incx, incy) = (ddx * scale, ddy * scale);
        sx += incx;
        sy += incy;
        if !(sx.abs() <= MAX_SHIFT && sy.abs() <= MAX_SHIFT) {
            return None;
        }
        if step < 1e-4 {
            return Some((sx, sy));
        }
        // Gauss-Newton on these residuals converges linearly: successive steps
        // point the same way and shrink by a steady ratio r. Then the rest of the
        // series is step * r / (1 - r) (Aitken); jump there and let the next
        // Gauss-Newton step correct. The stopping rule above is unchanged.
        if ACCEL_get() {
            if let Some((px, py)) = prev {
                let (n0, n1) = ((px * px + py * py).sqrt(), (incx * incx + incy * incy).sqrt());
                let cos = (px * incx + py * incy) / (n0 * n1).max(1e-300);
                let r = n1 / n0.max(1e-300);
                if cos > 0.95 && r > 0.05 && r < 0.9 {
                    let f = (r / (1.0 - r)).min(4.0);
                    sx += incx * f;
                    sy += incy * f;
                    prev = None; // the next step measures the corrected position
                    continue;
                }
            }
            prev = Some((incx, incy));
        }
    }
    Some((sx, sy))
}

// ---------------------------------------------------------------- search

/// Search point `i`: from the flat `pts` array when given, otherwise the
/// rectangle in Java's x-major order.
#[inline]
fn point(pts: Option<&[i32]>, sh: i32, i: usize) -> (i32, i32) {
    match pts {
        Some(p) => (p[2 * i], p[2 * i + 1]),
        None => ((i / sh as usize) as i32, (i % sh as usize) as i32),
    }
}

fn search<F>(count: usize, pts: Option<&[i32]>, sw: i32, sh: i32, parallel: bool, ssd: F) -> Acc
where
    F: Fn(i32, i32) -> Option<u64> + Sync,
{
    let visit = |acc: Acc, i: usize| {
        let (x, y) = point(pts, sh, i);
        if x >= sw || y >= sh || x < 0 || y < 0 { acc } else { acc.push(i, ssd(x, y)) }
    };
    if parallel {
        (0..count)
            .into_par_iter()
            .with_min_len(16)
            .fold(|| Acc::EMPTY, visit)
            .reduce(|| Acc::EMPTY, Acc::merge)
    } else {
        (0..count).fold(Acc::EMPTY, visit)
    }
}

#[allow(clippy::too_many_arguments)]
// ---------------------------------------------------------------- FFT (mode 12)

/// Exact SSD for every position of a rectangle search by FFT correlation.
///
/// Two complex images carry all channels: z1 = r + i g and z2 = b + i |I|^2
/// for the target, and t1 = r + i g, t2 = b - i/2 (opaque pixels only, 0 elsewhere)
/// for the template. The real part of corr(z1, t1) + corr(z2, t2) is
///
///   V(x) = sum T.I(x + k)  -  1/2 sum_opaque |I(x + k)|^2
///
/// so 2V is an integer and SSD(x) = sum |T|^2 - 2V(x). Four forward 2D FFTs and
/// one inverse give every position; 2V is rounded to the nearest integer, which
/// is exact because the f64 rounding error (~1e-5 here) is far below 1/2. If any
/// value lands more than 1/4 from an integer the result is discarded and the
/// caller falls back to a direct kernel.
///
/// Three passes, each one parallel loop (thread wake-ups are costly): row FFTs of
/// the four inputs; per column: four column FFTs, the spectrum product and its
/// inverse column FFT; per output row: the inverse row FFT and the SSDs.
mod fftsearch {
    use super::{Acc, Geometry};
    use rayon::prelude::*;
    use rustfft::num_complex::Complex64 as C;
    use rustfft::{Fft, FftPlanner};
    use std::cell::RefCell;
    use std::sync::{Arc, Mutex, OnceLock};

    const ZERO: C = C { re: 0.0, im: 0.0 };

    /// Smallest size >= n with only factors 2, 3, 5 and 7 (fast for rustfft).
    pub fn good_size(n: usize) -> usize {
        let mut m = n.max(1);
        loop {
            let mut k = m;
            for p in [2, 3, 5, 7] {
                while k % p == 0 {
                    k /= p;
                }
            }
            if k == 1 {
                return m;
            }
            m += 1;
        }
    }

    fn plan(n: usize, inverse: bool) -> Arc<dyn Fft<f64>> {
        static PLANNER: OnceLock<Mutex<FftPlanner<f64>>> = OnceLock::new();
        let mut p = PLANNER.get_or_init(|| Mutex::new(FftPlanner::new())).lock().unwrap();
        if inverse { p.plan_fft_inverse(n) } else { p.plan_fft_forward(n) }
    }

    thread_local! {
        /// inputs after the row pass (4 * p * q) and the column-pass output (p * q), reused
        static WORK: RefCell<(Vec<C>, Vec<C>)> = const { RefCell::new((Vec::new(), Vec::new())) };
    }

    pub fn search(g: &Geometry, target: &[i32], tr: &[i32], tg: &[i32], tb: &[i32], transp: &[u8],
                  sw: usize, sh: usize) -> Option<Acc> {
        WORK.with(|w| {
            let mut w = w.borrow_mut();
            let (ins, mid) = &mut *w;
            run(g, target, tr, tg, tb, transp, sw, sh, ins, mid)
        })
    }

    #[allow(clippy::too_many_arguments)]
    fn run(g: &Geometry, target: &[i32], tr: &[i32], tg: &[i32], tb: &[i32], transp: &[u8],
           sw: usize, sh: usize, ins: &mut Vec<C>, mid: &mut Vec<C>) -> Option<Acc> {
        let (w, wt, ht) = (g.w_test, g.wt, g.ht);
        let (wn, hn) = (sw + wt - 1, sh + ht - 1);
        let (p, q) = (good_size(wn), good_size(hn));
        let par = p * q >= 40_000 && rayon::current_num_threads() > 1;
        let n = p * q;
        let dbg = std::env::var_os("FFT_DBG").is_some();
        let t0 = std::time::Instant::now();
        ins.resize(4 * n, ZERO);
        mid.resize(n, ZERO);
        let (fp, fq, ip, iq) = (plan(p, false), plan(q, false), plan(p, true), plan(q, true));
        let mut t_sq: u64 = 0;
        for k in 0..wt * ht {
            if transp[k] == 0 {
                t_sq += (tr[k] * tr[k] + tg[k] * tg[k] + tb[k] * tb[k]) as u64;
            }
        }

        // pass 1: fill each row of the four inputs and FFT it (rows past the data stay zero)
        let fill_row = |r: usize, row: &mut [C], s: &mut Vec<C>| {
            let (img, y) = (r / q, r % q);
            row.fill(ZERO);
            match img {
                0 | 1 if y < hn => {
                    for (x, out) in row[..wn].iter_mut().enumerate() {
                        let v = target[y * w + x];
                        let (rr, gg, bb) = (((v >> 16) & 0xff) as f64, ((v >> 8) & 0xff) as f64, (v & 0xff) as f64);
                        *out = if img == 0 { C::new(rr, gg) } else { C::new(bb, rr * rr + gg * gg + bb * bb) };
                    }
                }
                2 | 3 if y < ht => {
                    for (x, out) in row[..wt].iter_mut().enumerate() {
                        let k = y * wt + x;
                        if transp[k] == 0 {
                            *out = if img == 2 {
                                C::new(tr[k] as f64, tg[k] as f64)
                            } else {
                                C::new(tb[k] as f64, -0.5)
                            };
                        }
                    }
                }
                _ => return,
            }
            s.resize(fp.get_inplace_scratch_len(), ZERO);
            fp.process_with_scratch(row, s);
        };
        if par {
            ins.par_chunks_mut(p).enumerate().for_each_init(Vec::new, |s, (r, row)| fill_row(r, row, s));
        } else {
            let mut s = Vec::new();
            ins.chunks_mut(p).enumerate().for_each(|(r, row)| fill_row(r, row, &mut s));
        }

        let t1 = t0.elapsed();
        // pass 2: per block of BLK columns kx: four column FFTs each, spectrum product,
        // inverse column FFT. Gathering BLK neighbouring columns per row reads whole
        // cache lines instead of one complex number per row.
        const BLK: usize = 8;
        let ins_ref: &[C] = ins;
        let columns = |kx0: usize, out: &mut [C], buf: &mut Vec<C>| {
            let nb = out.len() / q;
            let sl = fq.get_inplace_scratch_len().max(iq.get_inplace_scratch_len());
            buf.resize(4 * BLK * q + sl, ZERO);
            let (cols, s) = buf.split_at_mut(4 * BLK * q);
            for i in 0..4 {
                for y in 0..q {
                    let src = &ins_ref[i * n + y * p + kx0..i * n + y * p + kx0 + nb];
                    for (b, v) in src.iter().enumerate() {
                        cols[(i * BLK + b) * q + y] = *v;
                    }
                }
            }
            for i in 0..4 {
                for b in 0..nb {
                    fq.process_with_scratch(&mut cols[(i * BLK + b) * q..(i * BLK + b + 1) * q], s);
                }
            }
            for b in 0..nb {
                let o = &mut out[b * q..(b + 1) * q];
                let (c0, c1, c2, c3) = (b * q, (BLK + b) * q, (2 * BLK + b) * q, (3 * BLK + b) * q);
                for y in 0..q {
                    o[y] = cols[c0 + y] * cols[c2 + y].conj() + cols[c1 + y] * cols[c3 + y].conj();
                }
                iq.process_with_scratch(o, s);
            }
        };
        if par {
            mid.par_chunks_mut(BLK * q).enumerate().for_each_init(Vec::new, |b, (k, out)| columns(k * BLK, out, b));
        } else {
            let mut b = Vec::new();
            mid.chunks_mut(BLK * q).enumerate().for_each(|(k, out)| columns(k * BLK, out, &mut b));
        }

        let t2 = t0.elapsed();
        // pass 3: per block of BLK output rows y: inverse row FFTs, then the SSDs
        let mid_ref: &[C] = mid;
        let scale = 2.0 / n as f64;
        let rows_acc = |blk: usize, buf: &mut Vec<C>| -> Option<Acc> {
            let y0 = blk * BLK;
            let nb = BLK.min(sh - y0);
            buf.resize(BLK * p + ip.get_inplace_scratch_len(), ZERO);
            let (r, s) = buf.split_at_mut(BLK * p);
            for kx in 0..p {
                let src = &mid_ref[kx * q + y0..kx * q + y0 + nb];
                for (b, v) in src.iter().enumerate() {
                    r[b * p + kx] = *v;
                }
            }
            let mut acc = Acc::EMPTY;
            for b in 0..nb {
                let row = &mut r[b * p..(b + 1) * p];
                ip.process_with_scratch(row, s);
                let y = y0 + b;
                for (x, v) in row[..sw].iter().enumerate() {
                    let two_v = v.re * scale;
                    let k = two_v.round();
                    if !((two_v - k).abs() <= 0.25 && k <= t_sq as f64) {
                        return None;
                    }
                    acc = acc.push(x * sh + y, Some((t_sq as i64 - k as i64) as u64));
                }
            }
            Some(acc)
        };
        let blocks = sh.div_ceil(BLK);
        let merge = |a: Option<Acc>, b: Option<Acc>| match (a, b) {
            (Some(a), Some(b)) => Some(Acc::merge(a, b)),
            _ => None,
        };
        let r = if par {
            (0..blocks).into_par_iter().map_init(Vec::new, |b, k| rows_acc(k, b)).reduce(|| Some(Acc::EMPTY), merge)
        } else {
            let mut b = Vec::new();
            (0..blocks).map(|k| rows_acc(k, &mut b)).fold(Some(Acc::EMPTY), merge)
        };
        if dbg {
            eprintln!("fftphase {} {} {}", t1.as_micros(), (t2 - t1).as_micros(), (t0.elapsed() - t2).as_micros());
        }
        r
    }
}

fn run(mode: i32, target: &[i32], w_test: usize, tr: &[i32], tg: &[i32], tb: &[i32], transp: &[u8],
       wt: usize, ht: usize, pts: Option<&[i32]>, sw: i32, sh: i32) -> Acc {
    let g = Geometry { w_test, len: target.len(), wt, ht };
    let count = match pts {
        Some(p) => p.len() / 2,
        None => (sw.max(0) as usize) * (sh.max(0) as usize),
    };
    match mode {
        // auto: threads only pay off past ~1.5M pixel diffs (pool wake-up costs ~0.1 ms)
        7 => {
            let threads = rayon::current_num_threads() > 1;
            // FFT when its estimated time beats the direct kernels' (measured on an
            // i5-13450HX: ~18 ns per FFT grid cell, ~65 ps per pixel comparison on one
            // thread, ~15 ps + 0.3 ms fixed on all threads)
            if pts.is_none() && sw > 0 && sh > 0 {
                let opaque = transp.iter().filter(|&&t| t == 0).count() as f64;
                let ops = count as f64 * opaque;
                let direct = if threads { (65e-12 * ops).min(15e-12 * ops + 300e-6) } else { 65e-12 * ops };
                let cells = (fftsearch::good_size(sw as usize + wt - 1) * fftsearch::good_size(sh as usize + ht - 1)) as f64;
                let fft = 18e-9 * cells * if threads { 1.0 } else { 3.0 };
                if fft < direct {
                    return run(12, target, w_test, tr, tg, tb, transp, wt, ht, pts, sw, sh);
                }
            }
            let parallel = count * wt * ht > 1_500_000 && threads;
            run(if parallel { 11 } else { 10 }, target, w_test, tr, tg, tb, transp, wt, ht, pts, sw, sh)
        }
        // tiled + AVX2; same preconditions as the row-batched modes
        8 | 9 if pts.is_none()
            && sw > 0 && sh > 0
            && w_test >= sw as usize + wt - 1
            && g.len >= (sh as usize + ht - 1) * w_test
            && (wt * ht) as u64 * 195_075 < u32::MAX as u64 =>
        {
            let o = opaque(&g, tr, tg, tb, transp);
            let p = split_target::<i16>(target);
            search_tiled(&g, &o, &p, sw as usize, sh as usize, mode == 9)
        }
        // line searches (and rectangles the row kernels cannot take)
        8 | 9 => {
            let t = split_template_i16(tr, tg, tb, transp);
            let p = split_target::<i16>(target);
            let k = point_kernel();
            let parallel = mode == 9 && count * wt * ht > 1_500_000 && rayon::current_num_threads() > 1;
            search(count, pts, sw, sh, parallel, |x, y| k(&g, &t, &p, x, y))
        }
        // madd + prefix sums (AVX2 only); same preconditions as the row-batched modes
        #[cfg(target_arch = "x86_64")]
        10 | 11 if pts.is_none()
            && std::is_x86_feature_detected!("avx2")
            && sw > 0 && sh > 0
            && w_test >= sw as usize + wt - 1
            && g.len >= (sh as usize + ht - 1) * w_test
            && wt <= madd::PART_MAX =>
        {
            let p = madd::prepare(&g, target, tr, tg, tb, transp);
            search_by_rows(sw as usize, sh as usize, mode == 11, &|y, out| unsafe { madd::row(&p, y, out) })
        }
        // FFT correlation: every position at once; exact, else falls back to madd
        12 if pts.is_none()
            && sw > 0 && sh > 0
            && w_test >= sw as usize + wt
            && g.len >= (sh as usize + ht) * w_test =>
        {
            match fftsearch::search(&g, target, tr, tg, tb, transp, sw as usize, sh as usize) {
                Some(a) => a,
                None => run(11, target, w_test, tr, tg, tb, transp, wt, ht, pts, sw, sh),
            }
        }
        12 => run(11, target, w_test, tr, tg, tb, transp, wt, ht, pts, sw, sh),
        10 => run(8, target, w_test, tr, tg, tb, transp, wt, ht, pts, sw, sh),
        11 => run(9, target, w_test, tr, tg, tb, transp, wt, ht, pts, sw, sh),
        // benchmark floor: skip the search, report point 0 as the match
        99 => Acc { min: 1, idx: 0, sum: 2, n: 1, nan: false },
        0 => search(count, pts, sw, sh, false, |x, y| ssd_port(&g, target, tr, tg, tb, transp, x, y)),
        // row-batched needs a plain rectangle that fits the test image (always
        // true for Java's wTest = sw + wTemplate) and sums that fit in u32
        5 | 6 if pts.is_none()
            && sw > 0 && sh > 0
            && w_test >= sw as usize + wt - 1
            && g.len >= (sh as usize + ht - 1) * w_test
            && (wt * ht) as u64 * 195_075 < u32::MAX as u64 =>
        {
            let t = split_template_i16(tr, tg, tb, transp);
            let p = split_target::<i16>(target);
            search_rows(&g, &t, &p, sw as usize, sh as usize, mode == 6)
        }
        5 => run(3, target, w_test, tr, tg, tb, transp, wt, ht, pts, sw, sh),
        6 => run(4, target, w_test, tr, tg, tb, transp, wt, ht, pts, sw, sh),
        1 | 2 => {
            let t = split_template_i32(tr, tg, tb, transp);
            let p = split_target::<i32>(target);
            search(count, pts, sw, sh, mode == 2, |x, y| ssd_i32(&g, &t, &p, x, y))
        }
        _ => {
            let t = split_template_i16(tr, tg, tb, transp);
            let p = split_target::<i16>(target);
            search(count, pts, sw, sh, mode == 4, |x, y| ssd_i16(&g, &t, &p, x, y))
        }
    }
}

// ---------------------------------------------------------------- JNI

/// A primitive array pinned with GetPrimitiveArrayCritical.
struct Pinned {
    arr: jarray,
    ptr: *mut std::ffi::c_void,
    len: usize,
}

unsafe fn pin(env: *mut JNIEnv, arr: jarray, len: usize) -> Pinned {
    unsafe {
        let ptr = if arr.is_null() { null_mut() } else { ((**env).GetPrimitiveArrayCritical.unwrap())(env, arr, null_mut()) };
        Pinned { arr, ptr, len }
    }
}

unsafe fn unpin(env: *mut JNIEnv, p: &Pinned) {
    unsafe {
        if !p.ptr.is_null() {
            ((**env).ReleasePrimitiveArrayCritical.unwrap())(env, p.arr, p.ptr, JNI_ABORT);
        }
    }
}

unsafe fn length(env: *mut JNIEnv, arr: jarray) -> usize {
    unsafe { if arr.is_null() { 0 } else { ((**env).GetArrayLength.unwrap())(env, arr) as usize } }
}

/// `static native void search(int[] target, int wTest, int[] tR, int[] tG, int[] tB,
///     boolean[] transparent, int wTemplate, int hTemplate, int[] pts, int sw, int sh,
///     int mode, double[] out)`
///
/// `pts` is null for a full rectangle search, else flat [x0, y0, x1, y1, ...].
/// `out` receives {matchIndex, minDiffSq, sumDiffSq (NaN if any NaN), n}.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "system" fn Java_org_opensourcephysics_media_core_NativeMatcher_search(
    env: *mut JNIEnv, _cls: jclass,
    target: jintArray, w_test: jint, tr: jintArray, tg: jintArray, tb: jintArray,
    transp: jbooleanArray, wt: jint, ht: jint, pts: jintArray, sw: jint, sh: jint,
    mode: jint, out: jdoubleArray,
) {
    unsafe {
        // lengths first: no other JNI calls are allowed while arrays are pinned
        let arrays = [target, tr, tg, tb, transp, pts];
        let lens = arrays.map(|a| length(env, a));
        let pinned = [0, 1, 2, 3, 4, 5].map(|i| pin(env, arrays[i], lens[i]));
        let ints = |p: &Pinned| std::slice::from_raw_parts(p.ptr as *const i32, p.len);
        let tpl_len = (wt * ht) as usize;
        let acc = run(
            mode,
            ints(&pinned[0]),
            w_test as usize,
            &ints(&pinned[1])[..tpl_len],
            &ints(&pinned[2])[..tpl_len],
            &ints(&pinned[3])[..tpl_len],
            &std::slice::from_raw_parts(pinned[4].ptr as *const u8, pinned[4].len)[..tpl_len],
            wt as usize,
            ht as usize,
            if pinned[5].ptr.is_null() { None } else { Some(ints(&pinned[5])) },
            sw,
            sh,
        );
        for p in pinned.iter().rev() {
            unpin(env, p);
        }
        let result = [
            acc.idx as f64,
            if acc.idx < 0 { 1.0e20 } else { acc.min as f64 },
            if acc.nan { f64::NAN } else { acc.sum as f64 },
            acc.n as f64,
        ];
        ((**env).SetDoubleArrayRegion.unwrap())(env, out, 0, 4, result.as_ptr());
    }
}

/// `static native boolean refine(int[] target, int wTest, int[] tR, int[] tG, int[] tB,
///     boolean[] transparent, int wTemplate, int hTemplate, int xMatch, int yMatch,
///     double dx, double dy, double ux, double uy, double[] out)`
///
/// Sub-pixel offset of the match at test point (xMatch, yMatch), starting from
/// (dx, dy). (ux, uy) is a unit direction to stay on, or (0, 0) for 2D.
/// On success writes {dx, dy} to `out` and returns true.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "system" fn Java_org_opensourcephysics_media_core_NativeMatcher_refine(
    env: *mut JNIEnv, _cls: jclass,
    target: jintArray, w_test: jint, tr: jintArray, tg: jintArray, tb: jintArray,
    transp: jbooleanArray, wt: jint, ht: jint, xm: jint, ym: jint,
    dx: jdouble, dy: jdouble, ux: jdouble, uy: jdouble, out: jdoubleArray,
) -> jboolean {
    unsafe {
        if target.is_null() || tr.is_null() || tg.is_null() || tb.is_null() || transp.is_null() || wt <= 0 || ht <= 0 || w_test <= 0 {
            return JNI_FALSE;
        }
        let arrays = [target, tr, tg, tb, transp];
        let lens = arrays.map(|a| length(env, a));
        let tpl_len = (wt * ht) as usize;
        if lens[1..].iter().any(|&l| l < tpl_len) {
            return JNI_FALSE;
        }
        let pinned = [0, 1, 2, 3, 4].map(|i| pin(env, arrays[i], lens[i]));
        let ints = |p: &Pinned| std::slice::from_raw_parts(p.ptr as *const i32, p.len);
        let dir = if ux == 0.0 && uy == 0.0 { None } else { Some((ux, uy)) };
        let res = refine(
            ints(&pinned[0]),
            w_test as usize,
            &ints(&pinned[1])[..tpl_len],
            &ints(&pinned[2])[..tpl_len],
            &ints(&pinned[3])[..tpl_len],
            &std::slice::from_raw_parts(pinned[4].ptr as *const u8, pinned[4].len)[..tpl_len],
            wt as usize,
            ht as usize,
            xm,
            ym,
            (dx, dy),
            dir,
        );
        for p in pinned.iter().rev() {
            unpin(env, p);
        }
        match res {
            Some((rx, ry)) => {
                let r = [rx, ry];
                ((**env).SetDoubleArrayRegion.unwrap())(env, out, 0, 2, r.as_ptr());
                JNI_TRUE
            }
            None => JNI_FALSE,
        }
    }
}

/// `static native int threads()`: size of the rayon pool, for the benchmark report.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_opensourcephysics_media_core_NativeMatcher_threads(_env: *mut JNIEnv, _cls: jclass) -> jint {
    rayon::current_num_threads() as jint
}

/// Rectangle search for the kernel benchmark (examples/kbench.rs): (index, min).
#[doc(hidden)]
#[allow(clippy::too_many_arguments)]
pub fn bench_run(mode: i32, target: &[i32], w_test: usize, tr: &[i32], tg: &[i32], tb: &[i32], transp: &[u8],
                 wt: usize, ht: usize, sw: i32, sh: i32) -> (i64, u64, u64) {
    let a = run(mode, target, w_test, tr, tg, tb, transp, wt, ht, None, sw, sh);
    (a.idx, a.min, a.sum)
}

#[doc(hidden)]
#[allow(clippy::too_many_arguments)]
pub fn bench_refine(target: &[i32], w_test: usize, tr: &[i32], tg: &[i32], tb: &[i32], transp: &[u8],
                    wt: usize, ht: usize, xm: i32, ym: i32) -> Option<(f64, f64)> {
    refine(target, w_test, tr, tg, tb, transp, wt, ht, xm, ym, (0.3, -0.2), None)
}

/// `static native int findCheckerboard(int[] rgb, int w, int h, double[] out)`
///
/// rgb: 0xRRGGBB pixels, row-major. out receives {i, j, x, y} per inner corner
/// (x, y in pixel-centre coordinates); returns the number of corners written.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_org_opensourcephysics_media_core_NativeMatcher_findCheckerboard(
    env: *mut JNIEnv, _cls: jclass, rgb: jintArray, w: jint, h: jint, out: jdoubleArray,
) -> jint {
    unsafe {
        let (w, h) = (w.max(0) as usize, h.max(0) as usize);
        let n = length(env, rgb);
        let cap = length(env, out) / 4;
        if n < w * h || w == 0 || h == 0 {
            return 0;
        }
        let gray: Vec<f32> = {
            let p = pin(env, rgb, n);
            let px = std::slice::from_raw_parts(p.ptr as *const i32, n);
            let g = px[..w * h]
                .iter()
                .map(|&c| {
                    let (r, gg, b) = ((c >> 16) & 255, (c >> 8) & 255, c & 255);
                    0.299 * r as f32 + 0.587 * gg as f32 + 0.114 * b as f32
                })
                .collect();
            unpin(env, &p);
            g
        };
        let corners = checker::find(&gray, w, h);
        let m = corners.len().min(cap);
        let mut buf = Vec::with_capacity(m * 4);
        for c in &corners[..m] {
            buf.extend_from_slice(&[c.i as f64, c.j as f64, c.x, c.y]);
        }
        ((**env).SetDoubleArrayRegion.unwrap())(env, out, 0, buf.len() as jsize, buf.as_ptr());
        m as jint
    }
}
