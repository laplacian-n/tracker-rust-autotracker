// Tracker-Rust installer: one window, per-user install (no administrator
// rights), payload.zip embedded as a resource.
//
//   Tracker-Rust-Setup.exe              interactive
//   Tracker-Rust-Setup.exe /S [/D=dir]  silent (no desktop shortcut unless /desktop)
using System;
using System.ComponentModel;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Text;
using System.Windows.Forms;
using Microsoft.Win32;

[assembly: AssemblyTitle("Tracker-Rust Setup")]
[assembly: AssemblyProduct("Tracker-Rust autotracker")]
[assembly: AssemblyVersion(Build.Version)]
[assembly: AssemblyFileVersion(Build.Version)]

class Setup : Form
{
    readonly TextBox dirBox = new TextBox();
    readonly CheckBox desktop = new CheckBox();
    readonly CheckBox assoc = new CheckBox();
    readonly CheckBox launch = new CheckBox();
    readonly Button install = new Button();
    readonly Button cancel = new Button();
    readonly Button browse = new Button();
    readonly ProgressBar bar = new ProgressBar();
    readonly Label status = new Label();
    readonly Panel options = new Panel();
    readonly Panel progress = new Panel();
    readonly Panel done = new Panel();
    readonly BackgroundWorker worker = new BackgroundWorker();
    string installedDir;

    Setup()
    {
        Text = Build.Name + " " + Build.Version + " Setup";
        Font = new Font("Segoe UI", 9f);
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        AutoScaleMode = AutoScaleMode.Dpi;
        ClientSize = new Size(560, 360);
        try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }

        Panel header = new Panel { Dock = DockStyle.Top, Height = 78, BackColor = Color.White };
        PictureBox pic = new PictureBox { Location = new Point(18, 15), Size = new Size(48, 48), SizeMode = PictureBoxSizeMode.Zoom };
        try { pic.Image = new Icon(Icon, 48, 48).ToBitmap(); } catch { }
        Label title = new Label { Text = Build.Title + " " + Build.Version, Location = new Point(80, 16), AutoSize = true,
                                  Font = new Font("Segoe UI Semibold", 13f) };
        Label sub = new Label { Text = "Tracker 6.3.5 with a much faster AutoTracker", Location = new Point(82, 46), AutoSize = true,
                                ForeColor = Color.DimGray };
        header.Controls.AddRange(new Control[] { pic, title, sub });
        Panel line = new Panel { Dock = DockStyle.Top, Height = 1, BackColor = Color.Gainsboro };

        foreach (Panel p in new[] { options, progress, done })
        {
            p.Location = new Point(0, 80);
            p.Size = new Size(560, 230);
            Controls.Add(p);
        }

        // options page
        Label intro = new Label
        {
            Text = "Everything Tracker needs is included (Java runtime, Xuggle video support). " +
                   "It is installed for your user only and needs no administrator rights. " +
                   "An installed original Tracker is not changed.",
            Location = new Point(22, 14), Size = new Size(516, 52)
        };
        Label dirLabel = new Label { Text = "Install folder", Location = new Point(22, 74), AutoSize = true };
        dirBox.Location = new Point(24, 96); dirBox.Size = new Size(420, 25);
        dirBox.Text = Build.DefaultDir;
        browse.Text = "Browse..."; browse.Location = new Point(452, 94); browse.Size = new Size(86, 28);
        browse.Click += delegate
        {
            using (FolderBrowserDialog d = new FolderBrowserDialog())
            {
                d.Description = "Choose the install folder";
                d.SelectedPath = dirBox.Text;
                if (d.ShowDialog(this) == DialogResult.OK)
                    dirBox.Text = Path.Combine(d.SelectedPath, Build.Name);
            }
        };
        desktop.Text = "Create a desktop shortcut"; desktop.Checked = true;
        desktop.Location = new Point(24, 138); desktop.AutoSize = true;
        assoc.Text = "Open .trk and .trz files with Tracker-Rust"; assoc.Checked = true;
        assoc.Location = new Point(24, 164); assoc.AutoSize = true;
        Label lic = new Label
        {
            Text = "Free software under the GNU GPL. Unofficial build, not affiliated with the Tracker authors.",
            Location = new Point(22, 200), AutoSize = true, ForeColor = Color.DimGray
        };
        options.Controls.AddRange(new Control[] { intro, dirLabel, dirBox, browse, desktop, assoc, lic });

        // progress page
        status.Location = new Point(22, 60); status.Size = new Size(516, 24); status.Text = "Preparing...";
        bar.Location = new Point(24, 90); bar.Size = new Size(512, 22); bar.Maximum = 1000;
        progress.Controls.AddRange(new Control[] { status, bar });

        // finish page
        Label fin = new Label
        {
            Text = "Tracker-Rust is installed. Start it from the Start menu or the desktop shortcut.\n\n" +
                   "In the Autotracker window, tick \"Fast\" to use the fast autotracker; " +
                   "unticked, Tracker runs its original code.\n\n" +
                   "To remove it: Settings > Apps > Installed apps > Tracker-Rust.",
            Location = new Point(22, 14), Size = new Size(516, 120)
        };
        launch.Text = "Start Tracker-Rust now"; launch.Checked = true;
        launch.Location = new Point(24, 146); launch.AutoSize = true;
        done.Controls.AddRange(new Control[] { fin, launch });

        install.Text = "Install"; install.Size = new Size(96, 30); install.Location = new Point(338, 318);
        cancel.Text = "Cancel"; cancel.Size = new Size(96, 30); cancel.Location = new Point(444, 318);
        install.Click += OnInstall;
        cancel.Click += delegate { Close(); };
        AcceptButton = install;
        Panel line2 = new Panel { Location = new Point(0, 308), Size = new Size(560, 1), BackColor = Color.Gainsboro };
        Controls.AddRange(new Control[] { install, cancel, line2, line, header });

        progress.Visible = false;
        done.Visible = false;

        worker.WorkerReportsProgress = true;
        worker.DoWork += delegate(object s, DoWorkEventArgs e)
        {
            object[] a = (object[])e.Argument;
            Install((string)a[0], (bool)a[1], (bool)a[2], worker);
            e.Result = a[0];
        };
        worker.ProgressChanged += delegate(object s, ProgressChangedEventArgs e)
        {
            bar.Value = Math.Min(bar.Maximum, e.ProgressPercentage);
            if (e.UserState != null) status.Text = (string)e.UserState;
        };
        worker.RunWorkerCompleted += delegate(object s, RunWorkerCompletedEventArgs e)
        {
            if (e.Error != null)
            {
                MessageBox.Show(this, "Installation failed:\n" + e.Error.Message, Text, MessageBoxButtons.OK, MessageBoxIcon.Error);
                progress.Visible = false; options.Visible = true;
                install.Enabled = cancel.Enabled = true;
                return;
            }
            installedDir = (string)e.Result;
            progress.Visible = false; done.Visible = true;
            install.Text = "Finish"; install.Enabled = true;
            cancel.Visible = false;
        };
    }

    void OnInstall(object sender, EventArgs e)
    {
        if (done.Visible)
        {
            if (launch.Checked) Start(installedDir);
            Close();
            return;
        }
        string dir;
        try { dir = Path.GetFullPath(Environment.ExpandEnvironmentVariables(dirBox.Text.Trim())); }
        catch { MessageBox.Show(this, "That folder name is not valid.", Text); return; }
        if (!Build.IsAscii(dir))
        {
            MessageBox.Show(this, "Java cannot run from a folder whose name has non-English characters.\n" +
                "Please choose a folder with English letters only, for example\n" + Build.DefaultDir +
                "\n\n(Your videos and Tracker files can stay in any folder.)", Text,
                MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return;
        }
        string err = CheckTarget(dir);
        if (err != null)
        {
            if (MessageBox.Show(this, err + "\n\nInstall there anyway?", Text, MessageBoxButtons.YesNo,
                                MessageBoxIcon.Warning) != DialogResult.Yes) return;
        }
        while (Build.RunningIn(dir).Count > 0)
        {
            if (MessageBox.Show(this, "Tracker-Rust is running from this folder. Please close it, then press Retry.",
                                Text, MessageBoxButtons.RetryCancel, MessageBoxIcon.Information) != DialogResult.Retry) return;
        }
        options.Visible = false; progress.Visible = true;
        install.Enabled = cancel.Enabled = false;
        worker.RunWorkerAsync(new object[] { dir, desktop.Checked, assoc.Checked });
    }

    /// <summary>A warning when dir is a non-empty folder that is not an earlier install.</summary>
    static string CheckTarget(string dir)
    {
        if (!Directory.Exists(dir)) return null;
        if (Directory.GetFileSystemEntries(dir).Length == 0) return null;
        if (File.Exists(Path.Combine(dir, Build.ListFile)) || File.Exists(Path.Combine(dir, "Tracker-Rust.bat"))) return null;
        return "The folder " + dir + " already contains other files.";
    }

    static void Start(string dir)
    {
        try { Process.Start(new ProcessStartInfo(Path.Combine(dir, "Tracker-Rust.exe")) { WorkingDirectory = dir }); }
        catch { }
    }

    static void Install(string dir, bool desktopLink, bool associate, BackgroundWorker w)
    {
        Report(w, 0, "Removing the previous version...");
        Directory.CreateDirectory(dir);
        Build.RemoveInstalledFiles(dir, false);

        using (Stream s = Assembly.GetExecutingAssembly().GetManifestResourceStream("payload.zip"))
        using (ZipArchive zip = new ZipArchive(s, ZipArchiveMode.Read))
        {
            long total = 0, doneBytes = 0;
            foreach (ZipArchiveEntry en in zip.Entries) total += en.Length;
            StringBuilder top = new StringBuilder();
            System.Collections.Generic.HashSet<string> seen = new System.Collections.Generic.HashSet<string>(StringComparer.OrdinalIgnoreCase);
            string root = Path.GetFullPath(dir).TrimEnd('\\') + "\\";
            int last = -1;
            foreach (ZipArchiveEntry en in zip.Entries)
            {
                string rel = en.FullName.Replace('/', '\\');
                string head = rel.Split('\\')[0];
                if (head.Length > 0 && seen.Add(head)) top.AppendLine(head);
                string dest = Path.GetFullPath(Path.Combine(dir, rel));
                if (!dest.StartsWith(root, StringComparison.OrdinalIgnoreCase)) continue;
                if (rel.EndsWith("\\")) { Directory.CreateDirectory(dest); continue; }
                Directory.CreateDirectory(Path.GetDirectoryName(dest));
                en.ExtractToFile(dest, true);
                doneBytes += en.Length;
                int pct = (int)(900 * doneBytes / Math.Max(1, total));
                if (pct != last) { last = pct; Report(w, pct, "Copying " + rel); }
            }
            top.AppendLine(Build.ListFile);
            File.WriteAllText(Path.Combine(dir, Build.ListFile), top.ToString());
        }

        Report(w, 920, "Creating shortcuts...");
        string exe = Path.Combine(dir, "Tracker-Rust.exe");
        Build.CreateShortcut(Build.StartMenuLink, exe, dir, "Tracker with the fast Rust autotracker");
        if (desktopLink) Build.CreateShortcut(Build.DesktopLink, exe, dir, "Tracker with the fast Rust autotracker");
        else if (File.Exists(Build.DesktopLink))
        {
            // an earlier shortcut of this install (to the exe or the old .bat)
            string t = Build.ShortcutTarget(Build.DesktopLink);
            if (t != null && t.StartsWith(Path.GetFullPath(dir).TrimEnd('\\') + "\\", StringComparison.OrdinalIgnoreCase))
                File.Delete(Build.DesktopLink);
        }

        if (associate) { Report(w, 950, "Registering .trk and .trz files..."); Build.Associate(exe); }

        Report(w, 980, "Registering the uninstaller...");
        long kb = 0;
        foreach (string f in Directory.GetFiles(dir, "*", SearchOption.AllDirectories)) kb += new FileInfo(f).Length / 1024;
        using (RegistryKey k = Registry.CurrentUser.CreateSubKey(Build.UninstallKey))
        {
            string un = Path.Combine(dir, "Uninstall.exe");
            k.SetValue("DisplayName", Build.Name + " (Tracker 6.3.5 with fast autotracker)");
            k.SetValue("DisplayVersion", Build.Version);
            k.SetValue("Publisher", "laplacian-n");
            k.SetValue("DisplayIcon", "\"" + exe + "\",0");
            k.SetValue("InstallLocation", dir);
            k.SetValue("UninstallString", "\"" + un + "\"");
            k.SetValue("QuietUninstallString", "\"" + un + "\" /S");
            k.SetValue("URLInfoAbout", Build.Url);
            k.SetValue("HelpLink", Build.Url);
            k.SetValue("EstimatedSize", (int)Math.Min(int.MaxValue, kb), RegistryValueKind.DWord);
            k.SetValue("NoModify", 1, RegistryValueKind.DWord);
            k.SetValue("NoRepair", 1, RegistryValueKind.DWord);
            k.SetValue("InstallDate", DateTime.Now.ToString("yyyyMMdd"));
        }
        Report(w, 1000, "Done.");
    }

    static void Report(BackgroundWorker w, int p, string text)
    {
        if (w != null) w.ReportProgress(p, text);
    }

    [STAThread]
    static int Main(string[] args)
    {
        bool silent = false, desk = false;
        string dir = Build.DefaultDir;
        foreach (string a in args)
        {
            if (a.Equals("/S", StringComparison.OrdinalIgnoreCase)) silent = true;
            else if (a.Equals("/desktop", StringComparison.OrdinalIgnoreCase)) desk = true;
            else if (a.StartsWith("/D=", StringComparison.OrdinalIgnoreCase)) dir = Path.GetFullPath(a.Substring(3).Trim('"'));
        }
        if (silent)
        {
            try
            {
                if (!Build.IsAscii(dir)) return 3;
                if (Build.RunningIn(dir).Count > 0) return 2;
                Install(dir, desk, true, null);
                return 0;
            }
            catch (Exception e)
            {
                try { File.WriteAllText(Path.Combine(Path.GetTempPath(), "Tracker-Rust-setup-error.txt"), e.ToString()); } catch { }
                return 1;
            }
        }
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        Application.Run(new Setup());
        return 0;
    }
}
