# Eva AI Helper — Android AI Assistant

**Portfolio source repository** for Java extensions developed during recovery and continued development of **Eva**, an Android AI assistant with on-device language-model inference and task automation.

> **Source status:** This repository contains **15 Java extension files**, not the original complete Android Studio project. Eva's Kotlin/Gradle/C++ sources were unavailable after the original application was recovered from an installed APK. The recovered application can be rebuilt using a separate, private bytecode-recovery environment; **this repository alone cannot produce an APK**.

## What the code demonstrates

| Area | Implementation |
| --- | --- |
| Local AI integration | `GuestLocalInference.java` coordinates a local inference engine and manages timeouts, cancellation and access to a shared model mutex |
| Guest-message policy | `SmartGuestPolicy.java`, `SmartIntent.java` classify routine questions, restrict replies to configured property facts, and route sensitive questions to owner review |
| Automation engine | `HostAutomationCore.java` implements OFF/DRAFT/AUTO/SMART modes, quiet hours, cooldowns, deduplication, durable state and pause/stop controls |
| Android notifications | `NotificationReplyTransport.java` validates incoming host-app notifications and checks inline-reply capability before attempting a response |
| Storage | `EncryptedHostStore.java` encrypts locally stored settings using Android Keystore and AES-GCM |
| User interface | `GuestSetupActivity.java` provides configuration and preview controls |
| Additional logic | `ExistingCommandFixes.java`, `HostCommands.java` and supporting classes extend command processing, queues and owner alerts |

The guest-automation extensions target **Airbnb** and **Booking Pulse** notification workflows. They do **not** use official partner APIs, and available notification reply capabilities may vary by application and device.

## Repository layout

```text
feature-src/com/aihelper/extras/
  CoalescingWorkQueue.java
  EncryptedHostStore.java
  ExistingCommandFixes.java
  FeatureExtension.java
  GuestControl.java
  GuestInferenceSmoke.java
  GuestLocalInference.java
  GuestSetupActivity.java
  HostAutomationCore.java
  HostCommands.java
  NotificationReplyTransport.java
  OwnerAttention.java
  SmartGuestPolicy.java
  SmartIntent.java
  SmartReplyCoordinator.java
README.md
.gitignore
```

## Architecture and safety design

1. A supported Android notification is identified and checked to ensure it represents an incoming guest message.
2. Local classification and property-specific facts inform a policy decision.
3. Routine requests can receive a **policy-controlled** response; unknown details, emergencies, access-sensitive matters and financial or booking-change requests are routed for review.
4. Configuration, pause/stop state, quiet hours, cooldowns and notification action validity are rechecked before any eligible dispatch.
5. The Android notification inline-reply mechanism can submit a reply when its required capability is available. **Submission does not prove delivery to a guest.**

The engine includes draft-only and stopped modes. These safeguards are **implemented in the source**, not a claim of completed external security review or production reliability.

## Java regression tests

A minimal, **Android-independent** smoke suite checks the Java-only guest policy, model-intent parser and bounded coalescing queue. It uses synthetic strings and does not read notifications or send messages.

- 19 checks covering configured facts, non-disclosure and escalation, strict model JSON parsing, queue replacement and capacity
- Run automatically with **GitHub Actions / Java 17**: [Java extension smoke tests](.github/workflows/java-smoke.yml)
- Run locally with a JDK 17+ (Unix shell):

```bash
mkdir -p /tmp/eva-java-tests
javac -encoding UTF-8 -d /tmp/eva-java-tests \
  feature-src/com/aihelper/extras/SmartGuestPolicy.java \
  feature-src/com/aihelper/extras/SmartIntent.java \
  feature-src/com/aihelper/extras/CoalescingWorkQueue.java \
  tests/ExtensionPolicySmoke.java
java -cp /tmp/eva-java-tests com.aihelper.extras.ExtensionPolicySmoke
```

A successful smoke suite **does not** validate the recovered APK, notification integrations or actual LLM runtime.

## Build and testing status

- **Not standalone-buildable:** No Gradle project, full original app source, Android resources, recovered bytecode or compiled native inference engine is included here.
- **Not a downloadable APK:** This repository intentionally excludes the recovery build artifacts and signing material.
- Some extension classes depend on types and services available only in the recovered application.
- The standalone Java smoke suite runs in GitHub Actions; `GuestInferenceSmoke.java` is Android-dependent and **not** executed by that workflow.
- Actual end-to-end guest message delivery through third-party notifications has **not** been established by the repository contents.

For these reasons, this repository is best viewed as a **code and architecture showcase**, rather than installation instructions for an end-user product.

## Privacy and responsible use

- No model weights (such as `.gguf`), APKs, signing keys, passwords or guest databases are intended to be committed.
- Do not commit real customer messages, hotel credentials, device backups or business-specific secrets.
- Notification access and any automated communications must be authorized by the device owner and comply with the relevant service's rules.
- No public license is provided until the code's provenance and dependencies have been reviewed.

## Future engineering work

- Create a clean Android Studio/Gradle sample demonstrating the extensions independently of recovered bytecode.
- Add unit tests for policy classification, reply gating and state transitions.
- Document the device and Android API requirements once the standalone demo is available.
- Add sanitized UI screenshots and a short demonstration with simulated notifications.

**Technologies represented:** Java, Android notification APIs, local AI inference integration, Android Keystore, AES-GCM, concurrency control, and stateful automation.
