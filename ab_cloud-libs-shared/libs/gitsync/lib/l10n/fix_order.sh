#!/bin/bash

ref="app_en.arb"

mapfile -t ref_lines < "$ref"

declare -A ref_key_line
last_modified_key="@@last_modified"
last_modified_val=""

for i in "${!ref_lines[@]}"; do
    line="${ref_lines[i]}"
    if [[ $line =~ ^[[:space:]]*\"([^\"]+)\"[[:space:]]*:[[:space:]]*\"([^\"]*)\" ]]; then
        key="${BASH_REMATCH[1]}"
        val="${BASH_REMATCH[2]}"
        ref_key_line[$key]=$i
        if [[ "$key" == "$last_modified_key" ]]; then
            last_modified_val="$val"
        fi
    fi
done

for file in *.arb; do
    [[ "$file" == "$ref" ]] && continue

    declare -A file_kv
    declare -A file_raw

    while IFS= read -r line; do
        if [[ $line =~ ^([[:space:]]*)\"([^\"]+)\"[[:space:]]*:[[:space:]]*(.*?)(,?)[[:space:]]*$ ]]; then
            indent="${BASH_REMATCH[1]}"
            key="${BASH_REMATCH[2]}"
            val="${BASH_REMATCH[3]}"
            file_kv[$key]="$val"
            file_raw[$key]="$indent\"$key\": $val"
        fi
    done < "$file"

    output_lines=("${ref_lines[@]}")

    for key in "${!ref_key_line[@]}"; do
        idx=${ref_key_line[$key]}
        indent="$(echo "${ref_lines[idx]}" | grep -o '^[[:space:]]*')"

        if [[ "$key" == "$last_modified_key" ]]; then
            # always copy value from app_en.arb
            output_lines[$idx]="${indent}\"$key\": \"$last_modified_val\""
        elif [[ -v file_kv[$key] ]]; then
            val="${file_kv[$key]}"
            if [[ "$key" != "@@x-"* && "$val" == "\"\"," ]]; then
                output_lines[$idx]="${indent}"
            else
                output_lines[$idx]="${indent}\"$key\": $val"
            fi
        else
            output_lines[$idx]="${indent}"
        fi
    done

    extra_keys=()
    for key in "${!file_kv[@]}"; do
        if [[ ! -v ref_key_line[$key] ]]; then
            extra_keys+=("$key")
        fi
    done

    last_line_idx=$((${#output_lines[@]} - 1))
    while [[ $last_line_idx -ge 0 ]] && [[ ! "${output_lines[$last_line_idx]}" =~ ^[[:space:]]*\}$ ]]; do
        ((last_line_idx--))
    done
    insert_idx=$last_line_idx

    extra_lines=()
    for key in "${extra_keys[@]}"; do
        val="${file_kv[$key]}"
        extra_lines+=("    \"$key\": $val")
    done

    output_lines=("${output_lines[@]:0:$insert_idx}" "${extra_lines[@]}" "${output_lines[@]:$insert_idx}")

    key_line_indices=()
    for i in "${!output_lines[@]}"; do
        line="${output_lines[$i]}"
        [[ "$line" =~ ^[[:space:]]*\"[^\"]+\"[[:space:]]*: ]] && key_line_indices+=("$i")
    done

    last_key_idx="${key_line_indices[-1]}"
    for idx in "${key_line_indices[@]}"; do
        line="${output_lines[$idx]}"
        line="${line%,}"
        if [[ "$idx" != "$last_key_idx" ]]; then
            line="$line,"
        fi
        output_lines[$idx]="$line"
    done

    printf "%s\n" "${output_lines[@]}" > "$file.tmp"
    mv "$file.tmp" "$file"
done
