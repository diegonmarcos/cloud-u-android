/*
 * #771 -- run `#!` scripts that live on a noexec mount, in both terminals.
 *
 * THE PROBLEM. Android mounts shared storage (/storage/emulated/0, so ~/emulated and
 * ~/cloud-drive-shared-store) noexec, through a FUSE daemon that also reports no x bits.
 * That is a kernel mount option of a mount the app does not own: no app can lift it, and
 * proot does not help -- it checks access(X_OK) on the host path before it rewrites an
 * execve, so `./build.sh` in a shared-store repo is "Permission denied" in a shell and in
 * every agent (claude/goose/hermes run commands through a shell) alike.
 *
 * THE DESIGN. Do what the kernel's binfmt_script would have done, one level up: this
 * library is preloaded into every glibc process of the terminal (/etc/ld.so.preload in the
 * termux rootfs, /etc/ld-nix.so.preload in the nix terminal -- nixpkgs' glibc reads that
 * name). It wraps the exec entry points a shell, node (libuv), python (subprocess) or git
 * reaches through the PLT. A call runs exactly as before; ONLY when it fails with EACCES
 * and the target is a regular file on a mount whose statvfs carries ST_NOEXEC and that
 * starts with `#!` is it retried as `<interpreter> [<arg>] <script> <args...>` -- the
 * kernel's own argv rule -- with the interpreter, which lives in the exec-capable rootfs.
 * access()/faccessat()/eaccess() with X_OK answer yes for the same files only, so fish
 * (which checks X_OK before spawning) and `test -x` agree with what now runs. Nothing
 * changes for any file anywhere else: a non-executable script in $HOME stays
 * "Permission denied", as it should.
 *
 * THE LIMITS (also in the selftest's _doc):
 *  - Scripts only. An ELF binary on a noexec mount cannot run: the kernel refuses its
 *    PROT_EXEC mapping, and no user-space loader gets around that. Build outputs belong in
 *    $HOME (app-private, exec-capable), not in the shared store.
 *  - A script with no `#!` line is not run (the kernel would not either; a shell's own
 *    ENOEXEC fallback never sees it, because EACCES comes first).
 *  - Callers that do not go through glibc's PLT: statically linked binaries and Go
 *    programs (gh, sops) issue raw syscalls and keep the plain EACCES. Agents are not
 *    affected: they run commands through a shell, which is covered.
 *  - Symlinks cannot be created on Android shared storage at all (a FUSE property), so
 *    node_modules/.bin there is broken before exec is ever reached. Install dependencies
 *    under $HOME.
 *  - The nix terminal preloads by the profile path. A user-run `nix-on-droid switch` that
 *    drops this profile makes ld.so print "cannot be preloaded ... ignored" per process
 *    (harmless, loud) until /etc/ld-nix.so.preload is removed or the profile restored.
 *
 * Kept to symbols glibc 2.34 already has (the oldest glibc this is loaded into is the nix
 * bootstrap's 2.37, under /bin/sh): the nix build fails on anything newer.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <spawn.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <unistd.h>

extern char **environ;

/* The kernel reads at most this much of a `#!` line (BINPRM_BUF_SIZE). */
#define SHEBANG_MAX 256

static void *next(const char *name) {
    return dlsym(RTLD_NEXT, name);
}

/* path is a readable regular file on a noexec mount that starts with `#!`: split its line
 * into interp and the one optional arg (kernel rules) inside buf, and return 1. */
static int noexec_script(const char *path, char *buf, char **interp, char **arg) {
    struct statvfs vfs;
    struct stat st;
    if (statvfs(path, &vfs) != 0 || !(vfs.f_flag & ST_NOEXEC)) return 0;
    if (stat(path, &st) != 0 || !S_ISREG(st.st_mode)) return 0;
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    ssize_t got = read(fd, buf, SHEBANG_MAX - 1);
    close(fd);
    if (got < 3 || buf[0] != '#' || buf[1] != '!') return 0;
    buf[got] = '\0';
    char *end = strchr(buf, '\n');
    if (end == NULL) return 0;  /* longer than the kernel would read: refuse, as it does */
    *end = '\0';
    char *p = buf + 2;
    while (*p == ' ' || *p == '\t') p++;
    if (*p == '\0') return 0;
    *interp = p;
    while (*p != '\0' && *p != ' ' && *p != '\t') p++;
    *arg = NULL;
    if (*p != '\0') {
        *p++ = '\0';
        while (*p == ' ' || *p == '\t') p++;
        char *last = p + strlen(p);
        while (last > p && (last[-1] == ' ' || last[-1] == '\t' || last[-1] == '\r')) *--last = '\0';
        if (*p != '\0') *arg = p;
    }
    return 1;
}

/* argv for `interp [arg] script argv[1..]`, into out (room for argc + 3). */
static void script_argv(char **out, char *interp, char *arg, const char *script, char *const argv[]) {
    int i = 0;
    out[i++] = interp;
    if (arg != NULL) out[i++] = arg;
    out[i++] = (char *) script;
    for (int k = 1; argv != NULL && argv[0] != NULL && argv[k] != NULL; k++) out[i++] = argv[k];
    out[i] = NULL;
}

static int count(char *const argv[]) {
    int n = 0;
    while (argv != NULL && argv[n] != NULL) n++;
    return n;
}

/* The first PATH entry holding file as a noexec script, into cand; 1 if found. */
static int path_script(const char *file, char *cand, char *buf, char **interp, char **arg) {
    const char *path = getenv("PATH");
    if (path == NULL) path = "/bin:/usr/bin";
    size_t flen = strlen(file);
    while (*path != '\0') {
        const char *colon = strchr(path, ':');
        size_t dlen = colon ? (size_t) (colon - path) : strlen(path);
        if (dlen == 0) {
            if (flen + 1 <= PATH_MAX) { memcpy(cand, file, flen + 1); if (noexec_script(cand, buf, interp, arg)) return 1; }
        } else if (dlen + 1 + flen + 1 <= PATH_MAX) {
            memcpy(cand, path, dlen);
            cand[dlen] = '/';
            memcpy(cand + dlen + 1, file, flen + 1);
            if (noexec_script(cand, buf, interp, arg)) return 1;
        }
        if (colon == NULL) break;
        path = colon + 1;
    }
    return 0;
}

static int real_execve(const char *path, char *const argv[], char *const envp[]) {
    static int (*fn)(const char *, char *const[], char *const[]);
    if (fn == NULL) fn = next("execve");
    return fn(path, argv, envp);
}

/* After an exec of path failed with err: run it through its interpreter, or keep err. */
static int retry_execve(const char *path, char *const argv[], char *const envp[], int err) {
    char buf[SHEBANG_MAX], *interp, *arg;
    if (err != EACCES || !noexec_script(path, buf, &interp, &arg)) { errno = err; return -1; }
    char *nargv[count(argv) + 3];
    script_argv(nargv, interp, arg, path, argv);
    return real_execve(interp, nargv, envp);
}

int execve(const char *path, char *const argv[], char *const envp[]) {
    real_execve(path, argv, envp);
    return retry_execve(path, argv, envp, errno);
}

int execv(const char *path, char *const argv[]) {
    return execve(path, argv, environ);
}

int execvpe(const char *file, char *const argv[], char *const envp[]) {
    static int (*fn)(const char *, char *const[], char *const[]);
    if (fn == NULL) fn = next("execvpe");
    fn(file, argv, envp);
    int err = errno;
    if (err != EACCES) return -1;
    if (strchr(file, '/') != NULL) return retry_execve(file, argv, envp, err);
    char cand[PATH_MAX], buf[SHEBANG_MAX], *interp, *arg;
    if (!path_script(file, cand, buf, &interp, &arg)) { errno = err; return -1; }
    char *nargv[count(argv) + 3];
    script_argv(nargv, interp, arg, cand, argv);
    return real_execve(interp, nargv, envp);
}

int execvp(const char *file, char *const argv[]) {
    return execvpe(file, argv, environ);
}

typedef int (*spawn_fn)(pid_t *, const char *, const posix_spawn_file_actions_t *,
                        const posix_spawnattr_t *, char *const[], char *const[]);

static int real_spawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *fa,
                      const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    static spawn_fn fn;
    if (fn == NULL) fn = (spawn_fn) next("posix_spawn");
    return fn(pid, path, fa, attr, argv, envp);
}

int posix_spawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *fa,
                const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    int r = real_spawn(pid, path, fa, attr, argv, envp);
    char buf[SHEBANG_MAX], *interp, *arg;
    if (r != EACCES || !noexec_script(path, buf, &interp, &arg)) return r;
    char *nargv[count(argv) + 3];
    script_argv(nargv, interp, arg, path, argv);
    return real_spawn(pid, interp, fa, attr, nargv, envp);
}

int posix_spawnp(pid_t *pid, const char *file, const posix_spawn_file_actions_t *fa,
                 const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    static spawn_fn fn;
    if (fn == NULL) fn = (spawn_fn) next("posix_spawnp");
    int r = fn(pid, file, fa, attr, argv, envp);
    if (r != EACCES) return r;
    if (strchr(file, '/') != NULL) return posix_spawn(pid, file, fa, attr, argv, envp);
    char cand[PATH_MAX], buf[SHEBANG_MAX], *interp, *arg;
    if (!path_script(file, cand, buf, &interp, &arg)) return r;
    char *nargv[count(argv) + 3];
    script_argv(nargv, interp, arg, cand, argv);
    return real_spawn(pid, interp, fa, attr, nargv, envp);
}

/* X_OK is answered for a noexec script exactly as exec now treats it; every other bit,
 * and every other file, keeps the real answer. */
static int x_ok(int r, const char *path, int mode, int (*check)(const char *, int)) {
    char buf[SHEBANG_MAX], *interp, *arg;
    if (r == 0 || !(mode & X_OK) || errno != EACCES) return r;
    int err = errno;
    if ((mode & ~X_OK) != 0 && check(path, mode & ~X_OK) != 0) { errno = err; return r; }
    if (!noexec_script(path, buf, &interp, &arg)) { errno = err; return r; }
    return 0;
}

static int real_access(const char *path, int mode) {
    static int (*fn)(const char *, int);
    if (fn == NULL) fn = next("access");
    return fn(path, mode);
}

int access(const char *path, int mode) {
    return x_ok(real_access(path, mode), path, mode, real_access);
}

static int real_eaccess(const char *path, int mode) {
    static int (*fn)(const char *, int);
    if (fn == NULL) fn = next("eaccess");
    return fn(path, mode);
}

int eaccess(const char *path, int mode) {
    return x_ok(real_eaccess(path, mode), path, mode, real_eaccess);
}

int euidaccess(const char *path, int mode) {
    return eaccess(path, mode);
}

int faccessat(int dirfd, const char *path, int mode, int flags) {
    static int (*fn)(int, const char *, int, int);
    if (fn == NULL) fn = next("faccessat");
    int r = fn(dirfd, path, mode, flags);
    /* Only paths that resolve without dirfd; a relative path under another dirfd keeps
     * the real answer rather than being checked against the wrong directory. */
    if (dirfd != AT_FDCWD && path[0] != '/') return r;
    return x_ok(r, path, mode, (flags & AT_EACCESS) ? real_eaccess : real_access);
}
