# 0006 · Bundle every platform's library in the jar

**Status**: Accepted

## Context

The binding is useless without the `emu-core` native library, which [0001](0001-c-abi.md) and
[0002](0002-ffm.md) leave as a separate artifact. Shipping it beside the jar means every user downloads the right
file for their platform, checks it, and wires its path into the test JVM before the first call, and does it again on
every upgrade.

## Decision

A released jar carries one library per supported platform under `awsemulator/native/<os>-<arch>/`. `EmuCore.open()`
picks the current platform's, copies it to a temporary file once per JVM, and loads that file. The system property
`emu.core.library` takes precedence, for other platforms and for builds from source.

The release workflow builds the libraries on one runner per platform, stages them into the jar's resources, and
refuses to publish a jar that lacks any of them. It also attaches them to the GitHub Release, for hosts of the C ABI
other than the JVM.

## Alternatives

**A separate download**
:   The jar stays small, but the setup above falls on every user, and a version mismatch between jar and library goes
    unnoticed until a call misbehaves.

**One jar per platform, as Maven classifiers**
:   Users download only their platform's library, but must pick a classifier in their build, and CI machines that
    differ from laptops need different dependencies.

## Consequences

- Adding the dependency is the whole setup on a supported platform.
- The jar is as large as the three libraries together. That is acceptable for a test dependency, and the libraries
  are stripped to keep it down.
- A release cannot publish until every platform's library has built and passed its tests.
- The temporary copy is deleted when the JVM exits normally; a killed JVM leaves it behind in the temporary directory.
