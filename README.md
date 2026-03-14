# v2rayNG-Pro

Standalone Android Xray/V2Ray client fork based on the official [v2rayNG](https://github.com/2dust/v2rayNG) project. This fork is designed to install alongside the original app, ship under its own release line, and keep the upstream base version transparent inside the app.

[![API](https://img.shields.io/badge/API-24%2B-yellow.svg?style=flat)](https://developer.android.com/about/versions/lollipop)
[![Kotlin Version](https://img.shields.io/badge/Kotlin-2.3.0-blue.svg)](https://kotlinlang.org)
[![Latest Release](https://img.shields.io/github/v/release/tempereddev/v2rayNG?display_name=tag&logo=github)](https://github.com/tempereddev/v2rayNG/releases)
[![Downloads](https://img.shields.io/github/downloads/tempereddev/v2rayNG/latest/total?logo=github)](https://github.com/tempereddev/v2rayNG/releases)
[![Base Upstream](https://img.shields.io/badge/upstream-v2rayNG%202.0.15-blue)](https://github.com/2dust/v2rayNG)

## What This Fork Adds

- Separate Android package ID: `com.v2ray.ang.pro`
- Side-by-side installation with the official `v2rayNG`
- Independent update channel from this GitHub repository
- About screen shows both the fork version and the upstream base version
- Release line tailored for this fork without overwriting the original app

## Upstream Base

- Official upstream project: [2dust/v2rayNG](https://github.com/2dust/v2rayNG)
- Upstream base version used for this fork: `2.0.15`
- Upstream base commit: `d3118b5`

## Install And Update

- Install this fork beside the original app without replacing it
- In-app update checks target this repository's releases
- Release artifacts are published under the `v2rayNG-Pro` release line
- Version-by-version release notes now live in [CHANGELOG.md](CHANGELOG.md) and are reused for GitHub releases

## Automation

- A scheduled GitHub Actions workflow can sync new upstream `v2rayNG` commits into this fork
- The automation reapplies the fork-specific patch set on top of the new upstream base
- After merge to `master`, another workflow auto-tags the new fork version so the release build pipeline can run
- This means upstream changes can be adopted with much less manual work while keeping the fork identity intact

## Usage

### Geoip and Geosite

- geoip.dat and geosite.dat files are in `Android/data/com.v2ray.ang.pro/files/assets` (path may differ on some Android device)
- the download feature can use enhanced rules from [Loyalsoldier/v2ray-rules-dat](https://github.com/Loyalsoldier/v2ray-rules-dat)
- official domain and IP lists can be imported manually from [Loyalsoldier/v2ray-rules-dat](https://github.com/Loyalsoldier/v2ray-rules-dat) and [Loyalsoldier/geoip](https://github.com/Loyalsoldier/geoip)
- third-party `.dat` files can be placed in the same folder if needed

## Discoverability

If users search for phrases like `v2rayNG Pro`, `v2rayNG fork`, `Android Xray client`, `V2Ray Android proxy`, or `install alongside v2rayNG`, this repository and its release pages should now be easier to understand because the naming, README, release line, and About screen all point to the same identity.

## Development Guide

Android project under `V2rayNG/` can be compiled directly in Android Studio or with the Gradle wrapper. The `aar` can be compiled from [AndroidLibV2rayLite](https://github.com/2dust/AndroidLibV2rayLite) or [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite).

For WSA, VPN permission may need:

`appops set [package name] ACTIVATE_VPN allow`
