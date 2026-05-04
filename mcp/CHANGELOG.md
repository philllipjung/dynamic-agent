# Changelog

All notable changes to the Jaeger MCP tools collection.

## [2.0.0] - 2026-05-03 - Major Refactoring

### Added
- ✅ `lib/mcp_lib.sh` - Shared library with reusable functions
- ✅ `mcp.sh` - Main entry point for all operations
- ✅ Dynamic session management with auto-initialization
- ✅ Color-coded output (success/error/warning/info)
- ✅ Session validation and auto-renewal
- ✅ `mcp_init_session()` - Initialize MCP session
- ✅ `mcp_call()` - Generic tool calling function
- ✅ `mcp_parse_result()` - Parse SSE responses
- ✅ `mcp_has_error()` - Check for errors
- ✅ `mcp_get_error()` - Extract error messages
- ✅ `mcp_header/success/error/warning/info()` - Formatted output
- ✅ Comprehensive command-line interface via `mcp.sh`

### Changed
- 🔧 All scripts updated to use `lib/mcp_lib.sh`
- 🔧 `view_all.sh` - Now uses shared library, cleaner output
- 🔧 `test_tools.sh` - Better error handling, test counters
- 🔧 `save_results.sh` - Cleaner code with library functions
- 🔧 `health.sh` - Uses library for session management
- 🔧 `init_session.sh` - Now part of library (`mcp_init_session`)

### Removed
- 🗑️ `view_raw.sh` - Duplicate functionality merged into `view_all.sh`
- 🗑️ Hardcoded session IDs from all scripts
- 🗑️ Old JSON result files from results/ folder

### Fixed
- 🐛 Session expiration issues with auto-renewal
- 🐛 Duplicate code across multiple scripts
- 🐛 Inconsistent error handling

### Improved
- ⚡ Faster execution with shared session caching
- 📚 Better documentation with library function reference
- 🎨 Color-coded terminal output
- 🔧 Unified command-line interface
- 📊 Pass/fail counters in test output

### Migration Notes

**Old usage:**
```bash
./scripts/health.sh
./scripts/view_all.sh
```

**New usage:**
```bash
./mcp.sh health
./mcp.sh view all
```

All old scripts still work but are updated to use the library.

---

## [1.0.0] - 2026-03-23 - Initial Release

### Added
- ✅ `health.sh` - Quick health check
- ✅ `view_all.sh` - View all tools with formatted output
- ✅ `view_raw.sh` - Raw SSE output viewer
- ✅ `save_results.sh` - Save results to JSON files
- ✅ `test_tools.sh` - Test 3 previously failing tools
- ✅ `show_schemas.py` - View tool parameter schemas
- ✅ `init_session.sh` - Session initialization script
- ✅ `README.md` - Comprehensive usage guide

### Fixed
- 🐛 Parameter naming issues (serviceName → service_name)
- 🐛 Missing span_ids array in get_span_details
- 🐛 Session initialization issues

### Documented
- 📚 All 8 MCP tools and their parameters
- 📚 Common mistakes and solutions
- 📚 Obsolete proxy documentation
- 📚 Session management flow

---

## Version Summary

| Version | Date | Status | Key Changes |
|---------|------|--------|-------------|
| 2.0.0 | 2026-05-03 | ✅ Stable | Major refactoring, shared library |
| 1.0.0 | 2026-03-23 | ✅ Legacy | Initial release, basic scripts |
