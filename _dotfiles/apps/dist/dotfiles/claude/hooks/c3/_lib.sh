# Shared by the guard hooks: read the Bash command from the PreToolUse JSON on stdin.
CMD="$(node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{process.stdout.write(JSON.parse(s).tool_input.command||"")}catch(e){}})')"
block() { echo "BLOCKED by c3-infra hook $1: $2" >&2; exit 2; }
