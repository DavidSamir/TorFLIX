"""Backport Android 11 network discovery to the unchanged libtorrent4j 1.2 JNI API."""
from pathlib import Path
import sys

source = Path(sys.argv[1])
config = source / "include/libtorrent/config.hpp"
text = config.read_text()
old = "#define TORRENT_ANDROID\n"
assert text.count(old) == 1
text = text.replace(old, old + """
// Android 11 disallows binding NETLINK_ROUTE sockets. Modern Android builds
// use bionic's supported getifaddrs() implementation instead. This binary is
// loaded only on API 30+, while API 22-29 retain the original Maven binary.
#undef TORRENT_USE_NETLINK
#define TORRENT_USE_NETLINK 0
#undef TORRENT_USE_IFADDRS
#define TORRENT_USE_IFADDRS 1
""")
config.write_text(text)

notifier = source / "src/ip_notifier.cpp"
text = notifier.read_text()
text = text.replace('#include "libtorrent/assert.hpp"', '#include "libtorrent/assert.hpp"\n#include "libtorrent/deadline_timer.hpp"')
old = """#else
struct ip_change_notifier_impl final : ip_change_notifier
{
\texplicit ip_change_notifier_impl(io_service& ios)
\t\t: m_ios(ios) {}"""
assert text.count(old) == 1
text = text.replace(old, """#elif defined TORRENT_ANDROID
// Network change notifications cannot bind a netlink socket on Android 11.
// Polling re-enumerates addresses through bionic without that restricted bind.
struct ip_change_notifier_impl final : ip_change_notifier
{
\texplicit ip_change_notifier_impl(io_service& ios) : m_timer(ios) {}
\tvoid async_wait(std::function<void(error_code const&)> cb) override
\t{
\t\tm_timer.expires_from_now(std::chrono::seconds(30));
\t\tm_timer.async_wait(std::move(cb));
\t}
\tvoid cancel() override { m_timer.cancel(); }
private:
\tdeadline_timer m_timer;
};
""" + old)
notifier.write_text(text)

# The 2019 pkg-config generator is incompatible with current CMake's NDK
# compiler expressions. We build an APK library, not a pkg-config installation.
cmake = source / "CMakeLists.txt"
text = cmake.read_text()
text = text.replace("\tgenerate_and_install_pkg_config_file(torrent-rasterbar libtorrent-rasterbar)", "\t# No pkg-config metadata needed for the Android JNI binary.")
cmake.write_text(text)
