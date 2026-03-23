#!/usr/bin/env bash
set -euo pipefail

# Herald — External Dependency Installer
# Installs brew packages, creates the Obsidian vault, and sets up ~/.herald

HERALD_HOME="$HOME/.herald"
VAULT_NAME="Herald-Memory"
OBSIDIAN_VAULT_BASE="$HOME/Library/Mobile Documents/iCloud~md~obsidian/Documents"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[✓]${NC} $1"; }
warn()  { echo -e "${YELLOW}[!]${NC} $1"; }
fail()  { echo -e "${RED}[✗]${NC} $1"; }
step()  { echo -e "\n${GREEN}──── $1 ────${NC}"; }

# ── Homebrew ────────────────────────────────────────────────────────

step "Checking Homebrew"

if ! command -v brew &>/dev/null; then
    fail "Homebrew not found. Install it first: https://brew.sh"
    exit 1
fi
info "Homebrew found"

# ── Java 21+ ───────────────────────────────────────────────────────

step "Checking Java"

if command -v java &>/dev/null; then
    JAVA_VERSION=$(java -version 2>&1 | head -1 | sed 's/.*"\(.*\)".*/\1/' | cut -d. -f1)
    if [ "$JAVA_VERSION" -ge 21 ] 2>/dev/null; then
        info "Java $JAVA_VERSION found"
    else
        warn "Java $JAVA_VERSION found but Java 21+ required"
        echo "  brew install openjdk@21"
        read -p "  Install now? [y/N] " -n 1 -r; echo
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            brew install openjdk@21
        fi
    fi
else
    warn "Java not found"
    read -p "  Install OpenJDK 21 via brew? [y/N] " -n 1 -r; echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        brew install openjdk@21
    fi
fi

# ── Node.js (for herald-ui frontend build) ─────────────────────────

step "Checking Node.js"

if command -v node &>/dev/null; then
    NODE_VERSION=$(node -v | sed 's/v//' | cut -d. -f1)
    if [ "$NODE_VERSION" -ge 20 ] 2>/dev/null; then
        info "Node.js v$(node -v | sed 's/v//') found"
    else
        warn "Node.js v$(node -v | sed 's/v//') found but v20+ required"
        echo "  brew install node@20"
    fi
else
    warn "Node.js not found (needed for herald-ui frontend)"
    read -p "  Install via brew? [y/N] " -n 1 -r; echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        brew install node
    fi
fi

# ── Obsidian ───────────────────────────────────────────────────────

step "Checking Obsidian"

OBSIDIAN_CLI="/Applications/Obsidian.app/Contents/MacOS/Obsidian"
if [ -x "$OBSIDIAN_CLI" ]; then
    info "Obsidian found at $OBSIDIAN_CLI"

    # Check CLI is enabled by testing a command
    if $OBSIDIAN_CLI vault list &>/dev/null; then
        info "Obsidian CLI is working"
    else
        warn "Obsidian CLI not responding — make sure Obsidian is running and CLI is enabled"
        echo "  Settings → General → Command line interface → Toggle ON"
    fi
else
    warn "Obsidian not found"
    read -p "  Install Obsidian via brew cask? [y/N] " -n 1 -r; echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        brew install --cask obsidian
        warn "After installing: open Obsidian, enable CLI in Settings → General → CLI"
    fi
fi

# ── Google Workspace CLI (optional) ────────────────────────────────

step "Checking Google Workspace CLI (optional)"

if command -v gws &>/dev/null; then
    info "gws found ($(gws --version 2>&1 | head -1))"
else
    warn "gws not found (optional — needed for Gmail/Calendar/Drive skills)"
    read -p "  Install via brew? [y/N] " -n 1 -r; echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        brew install googleworkspace-cli
        echo "  Run 'gws auth login' after installing to set up OAuth"
    fi
fi

# ── LM Studio (optional, for local embeddings) ────────────────────

step "Checking LM Studio (optional)"

if [ -d "/Applications/LM Studio.app" ]; then
    info "LM Studio found"
else
    warn "LM Studio not found (optional — needed for local embeddings/vector search)"
    echo "  Download from: https://lmstudio.ai"
    echo "  After installing, load the nomic-embed-text model for embeddings"
fi

# ── Herald Home Directory ──────────────────────────────────────────

step "Setting up ~/.herald"

mkdir -p "$HERALD_HOME"
info "Created $HERALD_HOME"

if [ ! -f "$HERALD_HOME/.env" ] && [ -f ".env.example" ]; then
    cp .env.example "$HERALD_HOME/.env"
    info "Copied .env.example → $HERALD_HOME/.env (edit with your API keys)"
fi

# ── Obsidian Herald-Memory Vault ───────────────────────────────────

step "Setting up Herald-Memory Obsidian vault"

if [ -x "$OBSIDIAN_CLI" ] && $OBSIDIAN_CLI vault list &>/dev/null; then
    # Check if vault already exists
    if $OBSIDIAN_CLI vault list 2>/dev/null | grep -q "$VAULT_NAME"; then
        info "Herald-Memory vault already exists"
    else
        # Create vault directory
        VAULT_PATH="$OBSIDIAN_VAULT_BASE/$VAULT_NAME"
        if [ -d "$OBSIDIAN_VAULT_BASE" ]; then
            mkdir -p "$VAULT_PATH"
            info "Created vault directory at $VAULT_PATH"

            # Open the vault in Obsidian to register it
            open "obsidian://open?vault=$VAULT_NAME" 2>/dev/null || true
            sleep 2

            warn "Obsidian should have opened the vault. If not, open it manually."
        else
            warn "iCloud Obsidian directory not found at $OBSIDIAN_VAULT_BASE"
            echo "  Create the vault manually in Obsidian, name it '$VAULT_NAME'"
        fi
    fi

    # Create folder structure if vault exists
    VAULT_PATH="$OBSIDIAN_VAULT_BASE/$VAULT_NAME"
    if [ -d "$VAULT_PATH" ]; then
        for folder in Chat-Sessions Daily Research Projects People Reference Templates Migrated-Memory; do
            if [ ! -d "$VAULT_PATH/$folder" ]; then
                # Use Obsidian CLI to create a placeholder note (creates the folder)
                $OBSIDIAN_CLI create \
                    "vault=$VAULT_NAME" \
                    "path=$folder/.gitkeep.md" \
                    "content=Placeholder to create folder structure" \
                    overwrite &>/dev/null || mkdir -p "$VAULT_PATH/$folder"
            fi
        done
        info "Vault folder structure created"

        # Create welcome note
        $OBSIDIAN_CLI create \
            "vault=$VAULT_NAME" \
            "path=Welcome.md" \
            "content=# Herald Memory Vault

This vault is managed by Herald, your personal AI assistant.

## Folder Structure

- **Chat-Sessions/** — Archived conversations
- **Daily/** — Daily journals and briefings
- **Research/** — Web research and deep dives
- **Projects/** — Project-specific notes
- **People/** — Contact and people notes
- **Reference/** — How-tos and config snippets
- **Templates/** — Note templates
- **Migrated-Memory/** — Auto-migrated hot memory entries

## Usage

Herald automatically archives conversations and indexes this vault for semantic search. You can also browse and edit notes directly in Obsidian." \
            overwrite &>/dev/null && info "Created Welcome.md" || true
    fi
else
    warn "Skipping vault creation (Obsidian CLI not available)"
    echo "  Install and open Obsidian, then re-run this script"
fi

# ── Summary ────────────────────────────────────────────────────────

step "Setup Complete"

echo ""
echo "Next steps:"
echo "  1. Edit $HERALD_HOME/.env with your API keys"
echo "  2. Build:   make build-all"
echo "  3. Run:     make dev"
echo "  4. Or install as service: source .env && make install-all"
echo ""

# Check what's still needed
MISSING=()
command -v java &>/dev/null || MISSING+=("Java 21+")
command -v node &>/dev/null || MISSING+=("Node.js 20+")
[ -x "$OBSIDIAN_CLI" ] || MISSING+=("Obsidian")

if [ ${#MISSING[@]} -gt 0 ]; then
    warn "Still needed: ${MISSING[*]}"
fi
