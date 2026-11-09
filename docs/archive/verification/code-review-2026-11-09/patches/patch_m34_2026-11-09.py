# -*- coding: utf-8 -*-
"""M34 剩余落点：把吞掉 CancellationException 的 catch / runCatching 补上显式重抛。"""
import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def read(p):
    with io.open(p, "r", encoding="utf-8", newline="") as f:
        return f.read()


def write(p, s):
    with io.open(p, "w", encoding="utf-8", newline="") as f:
        f.write(s)


def patch(rel, pairs):
    p = os.path.join(ROOT, rel)
    s = read(p)
    crlf = "\r\n" in s
    for a, b in pairs:
        if crlf:
            a = a.replace("\n", "\r\n")
            b = b.replace("\n", "\r\n")
        n = s.count(a)
        if n != 1:
            print("FAIL [%s] count=%d anchor=%r" % (rel, n, a[:70]))
            sys.exit(1)
        s = s.replace(a, b)
    write(p, s)
    print("OK", rel, "crlf" if crlf else "lf")


# ---- MainViewModel：4 个 suspend 函数的 catch + 专辑循环内的 catch ----
patch("app/src/main/java/com/nasmusic/tv/ui/viewmodel/MainViewModel.kt", [
    (
        '        } catch (e: Exception) {\n'
        '            AppLog.e("NASMusic", "loadFavorites failed", e)',
        '        } catch (e: Exception) {\n'
        '            e.rethrowIfCancellation()\n'
        '            AppLog.e("NASMusic", "loadFavorites failed", e)',
    ),
    (
        '        } catch (e: Exception) {\n'
        '            AppLog.e("NASMusic", "loadGenres failed", e)',
        '        } catch (e: Exception) {\n'
        '            e.rethrowIfCancellation()\n'
        '            AppLog.e("NASMusic", "loadGenres failed", e)',
    ),
    (
        '            } catch (e: Exception) {\n'
        '                AppLog.e("NASMusic", "loadRandomSongs NAS failed", e)',
        '            } catch (e: Exception) {\n'
        '                e.rethrowIfCancellation()\n'
        '                AppLog.e("NASMusic", "loadRandomSongs NAS failed", e)',
    ),
    (
        '            } catch (e: Exception) {\n'
        '                AppLog.e("NASMusic", "loadRandomSongs network failed for',
        '            } catch (e: Exception) {\n'
        '                e.rethrowIfCancellation()\n'
        '                AppLog.e("NASMusic", "loadRandomSongs network failed for',
    ),
    (
        '                        } catch (e: Exception) {\n'
        '                            nasError = e\n'
        '                        }',
        '                        } catch (e: Exception) {\n'
        '                            e.rethrowIfCancellation()\n'
        '                            nasError = e\n'
        '                        }',
    ),
])

# ---- PlaylistImportViewModel：enrich 的 runCatching 吞取消 ----
patch("app/src/main/java/com/nasmusic/tv/ui/viewmodel/PlaylistImportViewModel.kt", [
    (
        '                                .onFailure { e ->\n'
        '                                    AppLog.w("PlaylistImportViewModel", "enrich \'${stub.title}\' failed: ${e.message}")\n'
        '                                }',
        '                                .onFailure { e ->\n'
        '                                    e.rethrowIfCancellation()\n'
        '                                    AppLog.w("PlaylistImportViewModel", "enrich \'${stub.title}\' failed: ${e.message}")\n'
        '                                }',
    ),
])

# ---- PlayStatsViewModel：backfill 的 runCatching 吞取消 ----
patch("app/src/main/java/com/nasmusic/tv/ui/viewmodel/PlayStatsViewModel.kt", [
    (
        '                    .onFailure { AppLog.w("PlayStatsViewModel", "backfill daily stats failed", it) }',
        '                    .onFailure {\n'
        '                        it.rethrowIfCancellation()\n'
        '                        AppLog.w("PlayStatsViewModel", "backfill daily stats failed", it)\n'
        '                    }',
    ),
])

print("ALL DONE")
