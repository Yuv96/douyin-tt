on run
	set launcherPath to POSIX path of (path to me)
	try
		set launcherDir to do shell script "/usr/bin/dirname " & quoted form of launcherPath
		set launchCommand to "DYTT_DIR=" & quoted form of launcherDir & "; " & ¬
			"cd \"$DYTT_DIR\" || exit 11; " & ¬
			"[ -f \"$DYTT_DIR/run_douyin_tt.py\" ] || exit 11; " & ¬
			"DYTT_PYTHON=''; " & ¬
			"for DYTT_CANDIDATE in \"$DYTT_DIR/.runtime/venv/bin/python\" " & ¬
			"/Library/Frameworks/Python.framework/Versions/3.12/bin/python3 " & ¬
			"/Library/Frameworks/Python.framework/Versions/3.13/bin/python3 " & ¬
			"/Library/Frameworks/Python.framework/Versions/3.11/bin/python3 " & ¬
			"/Library/Frameworks/Python.framework/Versions/3.10/bin/python3 " & ¬
			"/opt/homebrew/opt/python@3.12/bin/python3.12 /usr/local/opt/python@3.12/bin/python3.12 " & ¬
			"/opt/homebrew/opt/python@3.11/bin/python3.11 /usr/local/opt/python@3.11/bin/python3.11 " & ¬
			"/opt/homebrew/opt/python@3.10/bin/python3.10 /usr/local/opt/python@3.10/bin/python3.10 " & ¬
			"/opt/homebrew/bin/python3 /usr/local/bin/python3 /usr/bin/python3; do " & ¬
			"if [ -x \"$DYTT_CANDIDATE\" ] && \"$DYTT_CANDIDATE\" -I -B -c 'import sys, tkinter; sys.exit(sys.version_info < (3, 10))' >/dev/null 2>&1; then " & ¬
			"DYTT_PYTHON=\"$DYTT_CANDIDATE\"; break; fi; done; " & ¬
			"[ -n \"$DYTT_PYTHON\" ] || exit 12; " & ¬
			"PYTHONDONTWRITEBYTECODE=1 /usr/bin/nohup \"$DYTT_PYTHON\" -B \"$DYTT_DIR/run_douyin_tt.py\" >/dev/null 2>&1 </dev/null &"
		do shell script launchCommand
	on error number errorNumber
		if errorNumber is 11 then
			display alert "抖音TT无法启动" message "请将启动器与完整项目放在同一目录。"
		else if errorNumber is 12 then
			display alert "抖音TT无法启动" message "请先安装 Python 3.10 或更新版本（含 Tk）。"
		else
			display alert "抖音TT无法启动" message "请检查 Python 安装后重试。"
		end if
	end try
end run
