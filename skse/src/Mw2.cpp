#include "Game.h"

#include <SimpleIni.h>

#include <filesystem>
#include <bit>
#include <format>

// MW2's guns (docs/MW2.md). IW4L, the Rust MW2 runtime, runs hidden as a third process: it plays
// MW2's weapon code with a soldier that follows Minecraft's player, and draws only the gun and
// MW2's HUD into Local\SkyCraft_MW2_v1. Skyrim creates that mapping, starts the guest with the
// [MW2] settings of SkyCraft.ini, and composites the layer (Overlay.cpp). The guest is put in a
// job object that kills it when this Skyrim's handle to the job closes, however Skyrim ends.
namespace skycraft::Mw2
{
	namespace
	{
		template <class T>
		std::atomic_ref<T> Atomic(T& a_value)
		{
			return std::atomic_ref<T>(a_value);
		}

		constexpr std::uint64_t kGuestTimeoutMs = 3000;
		// How long the guest may take to open the mapping after it was started.
		constexpr auto kConnectWait = 120s;
		// What the guest does once its map is up: put the soldier in and start the match.
		constexpr wchar_t kGuestCmds[] = L"wait world; spawn 0; force_match_start";

		HANDLE            mapping = nullptr;
		std::uint8_t*     base = nullptr;
		std::uint32_t     overlayFront = 2;
		HANDLE            job = nullptr;
		float             renderScale = 1.0f;
		std::atomic<bool> enabled{ false };

		template <class T>
		T* At(std::uint64_t a_off)
		{
			return reinterpret_cast<T*>(base + a_off);
		}

		mw2::Header* Header() { return At<mw2::Header>(mw2::kOffHeader); }

		std::filesystem::path ExpandPath(const char* a_utf8)
		{
			const std::filesystem::path raw(reinterpret_cast<const char8_t*>(a_utf8));
			wchar_t                     buf[MAX_PATH * 2];
			const DWORD                 n = ::ExpandEnvironmentStringsW(raw.c_str(), buf, static_cast<DWORD>(std::size(buf)));
			return n > 0 && n <= std::size(buf) ? std::filesystem::path(buf) : raw;
		}

		bool Create()
		{
			const auto          size = mw2::kMappingBytes;
			SECURITY_ATTRIBUTES access{ sizeof(access), SharedWithThisUser(), FALSE };
			mapping = ::CreateFileMappingW(INVALID_HANDLE_VALUE, access.lpSecurityDescriptor ? &access : nullptr, PAGE_READWRITE,
				static_cast<DWORD>(size >> 32), static_cast<DWORD>(size & 0xFFFFFFFF), mw2::kMappingName);
			const DWORD created = ::GetLastError();
			if (access.lpSecurityDescriptor) {
				::LocalFree(access.lpSecurityDescriptor);
			}
			if (!mapping) {
				logger::error("MW2: CreateFileMapping failed ({})", created);
				return false;
			}
			const bool existed = created == ERROR_ALREADY_EXISTS;
			base = static_cast<std::uint8_t*>(::MapViewOfFile(mapping, FILE_MAP_ALL_ACCESS, 0, 0, 0));
			if (!base) {
				logger::error("MW2: MapViewOfFile failed ({})", ::GetLastError());
				::CloseHandle(mapping);
				mapping = nullptr;
				return false;
			}

			// A guest from an earlier Skyrim can still be on a mapping that outlived it. Its overlay
			// slots would no longer agree with ours, and this Skyrim starts its own: stop it first.
			if (existed && Atomic(Header()->magic).load(std::memory_order_acquire) == mw2::kMagic) {
				const auto pid = Atomic(Header()->guestPid).load(std::memory_order_acquire);
				const auto beat = Atomic(Header()->guestHeartbeatMs).load(std::memory_order_acquire);
				if (pid && beat && ::GetTickCount64() - beat < kGuestTimeoutMs) {
					if (HANDLE process = ::OpenProcess(PROCESS_TERMINATE | SYNCHRONIZE, FALSE, pid)) {
						::TerminateProcess(process, 0);
						::WaitForSingleObject(process, 3000);
						::CloseHandle(process);
						logger::info("MW2: stopped an earlier Skyrim's guest (pid {})", pid);
					}
				}
			}

			// Start clean: header, control, state and the overlay swap, every ring empty and no
			// inventory.
			std::memset(base, 0, mw2::kOffShotRing + mw2::kRingDataOff);
			std::memset(base + mw2::kOffHitRing, 0, mw2::kRingDataOff);
			std::memset(base + mw2::kOffUi, 0, mw2::kOffOverlayPixels - mw2::kOffUi);
			Header()->version = mw2::kVersion;
			Header()->hostHeartbeatMs = ::GetTickCount64();
			Atomic(Header()->magic).store(mw2::kMagic, std::memory_order_release);
			overlayFront = 2;

			logger::info("MW2: shared memory {} ({} MB, {})", "Local\\SkyCraft_MW2_v1", size >> 20, existed ? "reused" : "created");
			return true;
		}

		// Kill-on-close: the guest goes when Skyrim does, crash or not.
		void CreateJob()
		{
			job = ::CreateJobObjectW(nullptr, nullptr);
			if (!job) {
				logger::warn("MW2: no job object ({}); the guest won't close with Skyrim", ::GetLastError());
				return;
			}
			JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits{};
			limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
			if (!::SetInformationJobObject(job, JobObjectExtendedLimitInformation, &limits, sizeof(limits))) {
				logger::warn("MW2: job object limits failed ({}); the guest won't close with Skyrim", ::GetLastError());
				::CloseHandle(job);
				job = nullptr;
			}
		}

		// Started through Explorer, the guest's process id only arrives when it opens the mapping.
		// Then it joins the job, and this thread waits to log how it ended.
		void Watch(HANDLE a_process)
		{
			if (!a_process) {
				const auto deadline = std::chrono::steady_clock::now() + kConnectWait;
				std::uint32_t pid = 0;
				while (!(pid = Atomic(Header()->guestPid).load(std::memory_order_acquire))) {
					if (std::chrono::steady_clock::now() > deadline) {
						logger::warn("MW2: the guest hasn't connected after {} s; see its log in iw4l-artifacts\\logs", kConnectWait.count());
						return;
					}
					std::this_thread::sleep_for(250ms);
				}
				a_process = ::OpenProcess(PROCESS_SET_QUOTA | PROCESS_TERMINATE | SYNCHRONIZE | PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
				if (!a_process) {
					logger::warn("MW2: guest pid {} connected, but couldn't be opened ({})", pid, ::GetLastError());
					return;
				}
				logger::info("MW2: guest connected (pid {})", pid);
			}
			if (job && !::AssignProcessToJobObject(job, a_process)) {
				logger::warn("MW2: couldn't tie the guest to Skyrim ({}); it won't close with Skyrim", ::GetLastError());
			}
			::WaitForSingleObject(a_process, INFINITE);
			DWORD code = 0;
			::GetExitCodeProcess(a_process, &code);
			::CloseHandle(a_process);
			logger::warn("MW2: the guest exited (code {:#x})", code);
		}

		void StartGuest(std::filesystem::path a_exe, std::wstring a_args)
		{
			const auto dir = a_exe.parent_path().wstring();
			const auto com = ::CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
			const bool viaDesktop = Launcher::OpenFromDesktop(a_exe.wstring(), a_args, dir, SW_HIDE);
			if (SUCCEEDED(com)) {
				::CoUninitialize();
			}
			if (viaDesktop) {
				logger::info("MW2: started the guest {}", a_exe.string());
				Watch(nullptr);
				return;
			}
			std::wstring        command = L"\"" + a_exe.wstring() + L"\" " + a_args;
			STARTUPINFOW        si{ sizeof(si) };
			si.dwFlags = STARTF_USESHOWWINDOW;
			si.wShowWindow = SW_HIDE;
			PROCESS_INFORMATION pi{};
			if (!::CreateProcessW(nullptr, command.data(), nullptr, nullptr, FALSE, 0, nullptr, dir.c_str(), &si, &pi)) {
				logger::warn("MW2: couldn't start the guest {} (error {})", a_exe.string(), ::GetLastError());
				return;
			}
			::CloseHandle(pi.hThread);
			logger::info("MW2: started the guest {} (directly)", a_exe.string());
			Watch(pi.hProcess);
		}
	}

	void Install()
	{
		CSimpleIniA ini;
		ini.SetUnicode();
		ini.LoadFile("Data/SKSE/Plugins/SkyCraft.ini");
		if (!ini.GetBoolValue("MW2", "bEnable", false)) {
			logger::info("MW2: off (bEnable = 0)");
			return;
		}
		const auto exe = ExpandPath(ini.GetValue("MW2", "sGuestExe", ""));
		const auto games = ExpandPath(ini.GetValue("MW2", "sMw2Path", ""));
		const auto map = std::filesystem::path(reinterpret_cast<const char8_t*>(ini.GetValue("MW2", "sMap", "mp_rust"))).wstring();
		renderScale = std::clamp(static_cast<float>(ini.GetDoubleValue("MW2", "fRenderScale", 1.0)), 0.25f, 1.0f);
		if (exe.empty() || !std::filesystem::exists(exe)) {
			logger::warn("MW2: off: sGuestExe ({}) doesn't exist", exe.string());
			return;
		}
		if (games.empty() || !std::filesystem::exists(games / "zone")) {
			logger::warn("MW2: off: sMw2Path ({}) isn't the MW2 folder (no zone folder in it)", games.string());
			return;
		}
		if (!Create()) {
			return;
		}
		CreateJob();
		enabled = true;
		// No trailing backslash: before the closing quote it would escape it.
		auto gamesArg = games.wstring();
		while (gamesArg.ends_with(L'\\') || gamesArg.ends_with(L'/')) {
			gamesArg.pop_back();
		}
		auto args = std::format(L"--skycraft --games \"{}\" map {} --cmds \"{}\"", gamesArg, map, kGuestCmds);
		logger::info("MW2: on (map {}, render scale {:.2f})", std::filesystem::path(map).string(), renderScale);
		// Off the main thread: talking to Explorer takes a moment, and the watcher stays.
		std::thread(StartGuest, exe, std::move(args)).detach();
	}

	bool Enabled() { return enabled.load(std::memory_order_relaxed); }

	bool GuestAlive()
	{
		if (!base) {
			return false;
		}
		const auto beat = Atomic(Header()->guestHeartbeatMs).load(std::memory_order_acquire);
		return beat != 0 && ::GetTickCount64() - beat < kGuestTimeoutMs;
	}

	bool Ready()
	{
		if (!base) {
			return false;
		}
		auto* state = At<mw2::State>(mw2::kOffState);
		auto  seq = Atomic(state->seq);
		for (int attempt = 0; attempt < 16; ++attempt) {
			const auto s1 = seq.load(std::memory_order_acquire);
			if (s1 & 1) {
				_mm_pause();
				continue;
			}
			const auto flags = Atomic(state->flags).load(std::memory_order_relaxed);
			std::atomic_thread_fence(std::memory_order_acquire);
			if (seq.load(std::memory_order_relaxed) == s1) {
				return (flags & mw2::kStReady) != 0;
			}
		}
		return false;
	}

	float HotbarTop()
	{
		if (!base) {
			return 0.0f;
		}
		auto* state = At<mw2::State>(mw2::kOffState);
		auto  seq = Atomic(state->seq);
		for (int attempt = 0; attempt < 16; ++attempt) {
			const auto s1 = seq.load(std::memory_order_acquire);
			if (s1 & 1) {
				_mm_pause();
				continue;
			}
			const auto top = std::bit_cast<float>(Atomic(*reinterpret_cast<std::uint32_t*>(&state->hotbarTop)).load(std::memory_order_relaxed));
			std::atomic_thread_fence(std::memory_order_acquire);
			if (seq.load(std::memory_order_relaxed) == s1) {
				return std::isfinite(top) ? std::clamp(top, 0.0f, 1.0f) : 0.0f;
			}
		}
		return 0.0f;
	}

	void WriteHeader(std::uint32_t a_backBufferW, std::uint32_t a_backBufferH)
	{
		if (!base) {
			return;
		}
		const auto w = std::min(static_cast<std::uint32_t>(a_backBufferW * renderScale + 0.5f), mw2::kMaxOverlayW);
		const auto h = std::min(static_cast<std::uint32_t>(a_backBufferH * renderScale + 0.5f), mw2::kMaxOverlayH);
		Atomic(Header()->viewportW).store(w, std::memory_order_relaxed);
		Atomic(Header()->viewportH).store(h, std::memory_order_relaxed);
		Atomic(Header()->hostHeartbeatMs).store(::GetTickCount64(), std::memory_order_relaxed);
		Atomic(Header()->hostFrame).fetch_add(1, std::memory_order_release);
	}

	bool AcquireOverlayFrame()
	{
		if (!base) {
			return false;
		}
		auto& state = At<mw2::OverlayCtl>(mw2::kOffOverlayCtl)->state;
		if (!(Atomic(state).load(std::memory_order_acquire) & mw2::kOverlayDirty)) {
			return false;
		}
		const auto old = Atomic(state).exchange(overlayFront, std::memory_order_acq_rel);
		overlayFront = old & 3;
		return true;
	}

	const std::uint8_t* FrontPixels()
	{
		return base + mw2::kOffOverlayPixels + mw2::kOverlaySlotBytes * overlayFront;
	}

	const mw2::OverlaySlotHdr* FrontHeader()
	{
		return At<mw2::OverlaySlotHdr>(mw2::kOffOverlaySlotHdr + sizeof(mw2::OverlaySlotHdr) * overlayFront);
	}
}
