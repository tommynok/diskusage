#include <sys/types.h>
#include <sys/stat.h>
#include <errno.h>
#include <stdio.h>
#include <unistd.h>
#include <string.h>
#include <dirent.h>
#include <stdlib.h>

static const int sep = 0;
static char global_scan_root[1024] = {0};

static int is_skipped_dir(const char *path) {
  /* Always skip kernel virtual filesystems and pseudo trees */
  if (strcmp(path, "/proc") == 0 || strncmp(path, "/proc/", 6) == 0) return 1;
  if (strcmp(path, "/sys") == 0 || strncmp(path, "/sys/", 5) == 0) return 1;
  if (strcmp(path, "/dev") == 0 || strncmp(path, "/dev/", 5) == 0) return 1;
  if (strcmp(path, "/acct") == 0 || strncmp(path, "/acct/", 6) == 0) return 1;
  if (strcmp(path, "/config") == 0 || strncmp(path, "/config/", 8) == 0) return 1;
  if (strcmp(path, "/linkerconfig") == 0 || strncmp(path, "/linkerconfig/", 14) == 0) return 1;
  if (strcmp(path, "/data_mirror") == 0 || strncmp(path, "/data_mirror/", 13) == 0) return 1;
  if (strcmp(path, "/debug_ramdisk") == 0 || strncmp(path, "/debug_ramdisk/", 15) == 0) return 1;

  /* When scanning root "/", skip mirror / FUSE mount trees that duplicate /data/media */
  if (strcmp(global_scan_root, "/") == 0) {
    if (strcmp(path, "/mnt") == 0 || strncmp(path, "/mnt/", 5) == 0) return 1;
    if (strcmp(path, "/storage") == 0 || strncmp(path, "/storage/", 9) == 0) return 1;
  }

  return 0;
}

struct Entity {
  long long sizeInBlocks;
  long long sizeInBytes;
  const char *name;
  struct Entity *next;
  char isdir;
};

void scan_dir(const char *path, struct Entity *entity, dev_t cur_dev);

const char *getName(const char *path) {
  const char *res = strrchr(path, '/');
  if (res == NULL) return path;
  if (*(res + 1) == '\0') {
    if (res == path) return "/";
    return res;
  }
  return res + 1;
}

int nfiles = 0;

void dump_file(struct Entity *entity) {
  if (entity->isdir) {
    putchar('D');
  } else {
    putchar('F');
  }
  printf("%s", entity->name);
  putchar(sep);
  printf("%lld", entity->sizeInBlocks);
  putchar(sep);
  printf("%lld", entity->sizeInBytes);
  putchar(sep);
  nfiles++;
  if (nfiles % 10 == 0) fflush(stdout);
}

void dump(char type) {
  putchar(type);
}

const char *get_error() {
  switch(errno) {
    case EACCES:
      return "%c%s <No access>";
    case ENOENT:
    case ENOTDIR:
      return "%c%s <deleted>";
    default:
      return "%c%s <error>";
  }
}

void dump_error(char type, const char *path,
    long long sizeInBlocks, long long sizeInBytes) {
  printf(get_error(), type, getName(path));
  putchar(sep);
  printf("%lld", sizeInBlocks);
  putchar(sep);
  printf("%lld", sizeInBytes);
  putchar(sep);
}

struct Entity *make_entity_internal(
    const char *path,
    struct stat *stbuf) {
  struct Entity *e = malloc(sizeof(struct Entity));
  e->name = strdup(getName(path));
  e->sizeInBlocks = stbuf->st_blocks;
  e->sizeInBytes = stbuf->st_size;
  e->isdir = S_ISDIR(stbuf->st_mode);
  return e;
}

struct Entity *make_entity(const char *path, dev_t cur_dev) {
  struct stat stbuf;
  int res = lstat(path, &stbuf);
  if (res < 0) {
    return NULL;
  }
  // Skip directories on a different device so we don't cross mount points.
  // Regular files on a different st_dev (e.g. overlayfs lower-layer) are kept.
  if (S_ISDIR(stbuf.st_mode) && stbuf.st_dev != cur_dev) {
    return NULL;
  }
  return make_entity_internal(path, &stbuf);
}

char *makePath(const char *base, const char *name) {
  int baseLen = strlen(base);
  int nameLen = strlen(name);
  int slashNeeded = (baseLen > 0 && base[baseLen - 1] == '/') ? 0 : 1;
  char *res = (char*) malloc(baseLen + slashNeeded + nameLen + 1);
  memcpy(res, base, baseLen);
  if (slashNeeded) {
    res[baseLen] = '/';
    memcpy(res + baseLen + 1, name, nameLen);
    res[baseLen + 1 + nameLen] = 0;
  } else {
    memcpy(res + baseLen, name, nameLen);
    res[baseLen + nameLen] = 0;
  }
  return res;
}

void scan_dir(const char *path, struct Entity *dirEntity, dev_t cur_dev) {
  DIR *dir = opendir(path);

  if (dir == NULL) {
    dump_error('D', path, dirEntity->sizeInBlocks,
        dirEntity->sizeInBytes);
    dump('Z');
    return;
  }
  dump_file(dirEntity);

  /* We need to track sub_dev for each child dir; use a parallel list */
  struct SubDirEntry {
    struct Entity *entity;
    dev_t sub_dev;
    struct SubDirEntry *next;
  };
  struct SubDirEntry *dirFirst = NULL;
  struct SubDirEntry **dirLast = &dirFirst;

  struct dirent *entity;
  while ((entity = readdir(dir)) != NULL) {
    if (entity->d_name[0] == 0 || (entity->d_name[0] == '.' && (
          entity->d_name[1] == 0 || (
            entity->d_name[1] == '.' && entity->d_name[2] == 0))
        )) continue;
    const char *entityPath = makePath(path, entity->d_name);
    struct stat stbuf;
    int res = lstat(entityPath, &stbuf);
    if (res < 0) { free((void*)entityPath); continue; }

    if (S_ISDIR(stbuf.st_mode)) {
      if (is_skipped_dir(entityPath)) {
        free((void*)entityPath);
        continue;
      }
      /* Include this subdir regardless of st_dev (we'll scan it with its own dev) */
      struct Entity *e = make_entity_internal(entityPath, &stbuf);
      free((void*)entityPath);
      struct SubDirEntry *se = (struct SubDirEntry*)malloc(sizeof(struct SubDirEntry));
      se->entity = e;
      se->sub_dev = stbuf.st_dev;
      se->next = NULL;
      *dirLast = se;
      dirLast = &(se->next);
    } else {
      /* Files: skip if on a different device (pure bind-mounted single files) */
      if (stbuf.st_dev != cur_dev) { free((void*)entityPath); continue; }
      struct Entity *e = make_entity_internal(entityPath, &stbuf);
      free((void*)entityPath);
      dump_file(e);
      free((void*)e->name);
      free(e);
    }
  }

  closedir(dir);

  struct SubDirEntry *se = dirFirst;
  while (se != NULL) {
    const char *entityPath = makePath(path, se->entity->name);
    /* Each subdir is scanned with its own st_dev, so cross-mount subdirs work */
    scan_dir(entityPath, se->entity, se->sub_dev);
    free((void*)entityPath);
    free((void*)se->entity->name);
    free(se->entity);
    struct SubDirEntry *next = se->next;
    free(se);
    se = next;
  }
  dump('Z');
}

void scan_tree(const char *path) {
  struct stat stbuf;
  int res = lstat(path, &stbuf);
  if (res == -1) {
    dump_error('D', path, 1, 0);
    dump('Z');
    fflush(stdout);
    return;
  }
  scan_dir(path, make_entity_internal(path, &stbuf), stbuf.st_dev);
  fflush(stdout);
}

int main(int argc, char **argv) {
  if (argc < 2 || argv[1] == NULL) {
    fprintf(stderr, "Need directory argument\n");
    exit(1);
  }
  if (strchr(argv[1], '/') == NULL) {
    fprintf(stderr, "Need absolute path\n");
    exit(1);
  }
  char *path = argv[1];
  int len = strlen(path);
  while (len > 1 && path[len - 1] == '/') {
    path[len - 1] = '\0';
    len--;
  }
  strncpy(global_scan_root, path, sizeof(global_scan_root) - 1);
  putchar(0);
  fflush(stdout);
  scan_tree(path);
  return 0;
}
