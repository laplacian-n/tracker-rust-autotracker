//! The Windows file dialog (IFileOpenDialog / IFileSaveDialog, the File Explorer
//! style picker) for Tracker's file choosers.
//!
//! `static native String[] fileDialog(int flags, String title, String okLabel,
//!     String folder, String fileName, String[] filterNames, String[] filterSpecs,
//!     int filterIndex)` on `NativeMatcher`:
//!
//! - flags: 1 = save dialog, 2 = multiple selection, 4 = pick folders
//! - filterSpecs: e.g. "*.trk;*.trz"; filterIndex is 0-based
//! - returns null if cancelled, an empty array if the dialog could not be shown,
//!   else {chosen filter index (0-based), path, path, ...}
//!
//! Call it from a thread of its own: it initialises COM single-threaded there.

use jni_sys::*;
use std::ptr::null_mut;

#[cfg(windows)]
mod win {
    use windows::Win32::Foundation::{ERROR_CANCELLED, HWND};
    use windows::Win32::System::Com::{
        CLSCTX_INPROC_SERVER, COINIT_APARTMENTTHREADED, COINIT_DISABLE_OLE1DDE, CoCreateInstance, CoInitializeEx,
        CoTaskMemFree, CoUninitialize,
    };
    use windows::Win32::System::Threading::GetCurrentProcessId;
    use windows::Win32::UI::Shell::Common::COMDLG_FILTERSPEC;
    use windows::Win32::UI::Shell::{
        FILEOPENDIALOGOPTIONS, FOS_ALLOWMULTISELECT, FOS_FILEMUSTEXIST, FOS_FORCEFILESYSTEM, FOS_NOCHANGEDIR,
        FOS_OVERWRITEPROMPT, FOS_PATHMUSTEXIST, FOS_PICKFOLDERS, FileOpenDialog, FileSaveDialog, IFileDialog,
        IFileOpenDialog, IFileSaveDialog, IShellItem, SHCreateItemFromParsingName, SIGDN_FILESYSPATH,
    };
    use windows::Win32::UI::WindowsAndMessaging::{GetForegroundWindow, GetWindowThreadProcessId};
    use windows::core::{HRESULT, HSTRING, Interface, PCWSTR, Result};

    pub struct Request {
        pub save: bool,
        pub multi: bool,
        pub folders: bool,
        pub title: Option<String>,
        pub ok_label: Option<String>,
        pub folder: Option<String>,
        pub file_name: Option<String>,
        pub filters: Vec<(String, String)>,
        pub filter_index: u32,
    }

    /// The foreground window if it belongs to this process (the Tracker window
    /// the command came from), so the dialog is modal to it.
    fn owner() -> Option<HWND> {
        unsafe {
            let h = GetForegroundWindow();
            if h.0.is_null() {
                return None;
            }
            let mut pid = 0u32;
            GetWindowThreadProcessId(h, Some(&mut pid));
            if pid == GetCurrentProcessId() { Some(h) } else { None }
        }
    }

    fn path_of(item: &IShellItem) -> Result<String> {
        unsafe {
            let p = item.GetDisplayName(SIGDN_FILESYSPATH)?;
            let s = p.to_string().unwrap_or_default();
            CoTaskMemFree(Some(p.0 as *const _));
            Ok(s)
        }
    }

    /// Ok(None) when the user cancels.
    pub fn show(r: &Request) -> Result<Option<(u32, Vec<String>)>> {
        unsafe {
            let init = CoInitializeEx(None, COINIT_APARTMENTTHREADED | COINIT_DISABLE_OLE1DDE);
            let result = run(r);
            if init.is_ok() {
                CoUninitialize();
            }
            result
        }
    }

    unsafe fn run(r: &Request) -> Result<Option<(u32, Vec<String>)>> {
        unsafe {
            let dlg: IFileDialog = if r.save {
                CoCreateInstance::<_, IFileSaveDialog>(&FileSaveDialog, None, CLSCTX_INPROC_SERVER)?.cast()?
            } else {
                CoCreateInstance::<_, IFileOpenDialog>(&FileOpenDialog, None, CLSCTX_INPROC_SERVER)?.cast()?
            };

            let mut opts: FILEOPENDIALOGOPTIONS = dlg.GetOptions()?;
            opts |= FOS_FORCEFILESYSTEM | FOS_NOCHANGEDIR | FOS_PATHMUSTEXIST;
            // Tracker asks before replacing a file itself
            opts &= !FOS_OVERWRITEPROMPT;
            if !r.save && !r.folders {
                opts |= FOS_FILEMUSTEXIST;
            }
            if r.multi && !r.save {
                opts |= FOS_ALLOWMULTISELECT;
            }
            if r.folders {
                opts |= FOS_PICKFOLDERS;
            }
            dlg.SetOptions(opts)?;

            // the HSTRINGs must outlive SetFileTypes
            let names: Vec<HSTRING> = r.filters.iter().map(|f| HSTRING::from(f.0.as_str())).collect();
            let specs: Vec<HSTRING> = r.filters.iter().map(|f| HSTRING::from(f.1.as_str())).collect();
            if !r.folders && !names.is_empty() {
                let items: Vec<COMDLG_FILTERSPEC> = names
                    .iter()
                    .zip(&specs)
                    .map(|(n, s)| COMDLG_FILTERSPEC { pszName: PCWSTR(n.as_ptr()), pszSpec: PCWSTR(s.as_ptr()) })
                    .collect();
                dlg.SetFileTypes(&items)?;
                dlg.SetFileTypeIndex(r.filter_index.min(items.len() as u32 - 1) + 1)?;
            }
            if let Some(t) = &r.title {
                dlg.SetTitle(&HSTRING::from(t.as_str()))?;
            }
            if let Some(t) = &r.ok_label {
                dlg.SetOkButtonLabel(&HSTRING::from(t.as_str()))?;
            }
            if let Some(d) = &r.folder {
                if let Ok(item) = SHCreateItemFromParsingName::<_, _, IShellItem>(&HSTRING::from(d.as_str()), None) {
                    let _ = dlg.SetFolder(&item);
                }
            }
            if let Some(n) = &r.file_name {
                dlg.SetFileName(&HSTRING::from(n.as_str()))?;
            }

            if let Err(e) = dlg.Show(owner().unwrap_or_default()) {
                if e.code() == HRESULT::from_win32(ERROR_CANCELLED.0) {
                    return Ok(None);
                }
                return Err(e);
            }

            let index = dlg.GetFileTypeIndex().unwrap_or(1).max(1) - 1;
            let mut paths = Vec::new();
            if r.multi && !r.save {
                let open: IFileOpenDialog = dlg.cast()?;
                let items = open.GetResults()?;
                for i in 0..items.GetCount()? {
                    paths.push(path_of(&items.GetItemAt(i)?)?);
                }
            } else {
                paths.push(path_of(&dlg.GetResult()?)?);
            }
            paths.retain(|p| !p.is_empty());
            Ok(Some((index, paths)))
        }
    }
}

unsafe fn jstring_to_string(env: *mut JNIEnv, s: jstring) -> Option<String> {
    unsafe {
        if s.is_null() {
            return None;
        }
        let len = ((**env).GetStringLength.unwrap())(env, s) as usize;
        let chars = ((**env).GetStringChars.unwrap())(env, s, null_mut());
        if chars.is_null() {
            return None;
        }
        let v = String::from_utf16_lossy(std::slice::from_raw_parts(chars, len));
        ((**env).ReleaseStringChars.unwrap())(env, s, chars);
        Some(v)
    }
}

unsafe fn new_jstring(env: *mut JNIEnv, s: &str) -> jstring {
    unsafe {
        let w: Vec<u16> = s.encode_utf16().collect();
        ((**env).NewString.unwrap())(env, w.as_ptr(), w.len() as jsize)
    }
}

unsafe fn string_array(env: *mut JNIEnv, arr: jobjectArray) -> Vec<String> {
    unsafe {
        if arr.is_null() {
            return Vec::new();
        }
        let n = ((**env).GetArrayLength.unwrap())(env, arr);
        (0..n)
            .map(|i| {
                let o = ((**env).GetObjectArrayElement.unwrap())(env, arr, i);
                let s = jstring_to_string(env, o).unwrap_or_default();
                ((**env).DeleteLocalRef.unwrap())(env, o);
                s
            })
            .collect()
    }
}

unsafe fn result_array(env: *mut JNIEnv, items: &[String]) -> jobjectArray {
    unsafe {
        let cls = ((**env).FindClass.unwrap())(env, c"java/lang/String".as_ptr());
        if cls.is_null() {
            return null_mut();
        }
        let arr = ((**env).NewObjectArray.unwrap())(env, items.len() as jsize, cls, null_mut());
        for (i, s) in items.iter().enumerate() {
            let js = new_jstring(env, s);
            ((**env).SetObjectArrayElement.unwrap())(env, arr, i as jsize, js);
            ((**env).DeleteLocalRef.unwrap())(env, js);
        }
        arr
    }
}

#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "system" fn Java_org_opensourcephysics_media_core_NativeMatcher_fileDialog(
    env: *mut JNIEnv, _cls: jclass,
    flags: jint, title: jstring, ok_label: jstring, folder: jstring, file_name: jstring,
    filter_names: jobjectArray, filter_specs: jobjectArray, filter_index: jint,
) -> jobjectArray {
    unsafe {
        #[cfg(windows)]
        {
            let names = string_array(env, filter_names);
            let specs = string_array(env, filter_specs);
            let req = win::Request {
                save: flags & 1 != 0,
                multi: flags & 2 != 0,
                folders: flags & 4 != 0,
                title: jstring_to_string(env, title).filter(|s| !s.is_empty()),
                ok_label: jstring_to_string(env, ok_label).filter(|s| !s.is_empty()),
                folder: jstring_to_string(env, folder).filter(|s| !s.is_empty()),
                file_name: jstring_to_string(env, file_name).filter(|s| !s.is_empty()),
                filters: names.into_iter().zip(specs).collect(),
                filter_index: filter_index.max(0) as u32,
            };
            match win::show(&req) {
                Ok(None) => null_mut(),
                Ok(Some((index, paths))) if !paths.is_empty() => {
                    let mut out = vec![index.to_string()];
                    out.extend(paths);
                    result_array(env, &out)
                }
                _ => result_array(env, &[]),
            }
        }
        #[cfg(not(windows))]
        {
            let _ = (flags, title, ok_label, folder, file_name, filter_names, filter_specs, filter_index);
            result_array(env, &[])
        }
    }
}
