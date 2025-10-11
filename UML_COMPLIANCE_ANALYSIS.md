# UML State Machine Compliance Analysis for VSM

**Analysis Date:** October 2025
**Target Specification:** UML 2.5 State Machine Specification

---

## Executive Summary

This document analyzes the current VSM (Visual State Machine) implementation against the UML 2.5 State Machine specification and identifies the gaps that need to be addressed for full compliance. The VSM project already implements many core UML state machine features, but several advanced features are missing.

**Estimated effort for full compliance:** 8-11 weeks

---

## Currently Implemented Features

The VSM implementation already includes several core UML state machine features:

### States & Hierarchy

- **Simple States** - Basic states (`State` interface in `FSM.java:147`)
- **Composite States** - States containing nested FSMs (`FSMState` in `FSM.java:167`)
- **Hierarchical State Machines** - FSMs nested within states
- **Initial State** - Entry point for state machines (`FSM.java:109-110`)
- **Final States** - Multiple terminal states (`FSM.java:113-115`)
- **Error State** - Exception handling state (`FSM.java:116-119`)

### Transitions & Behaviors

- **Guarded Transitions** - Conditions on transitions (`Guard` interface)
- **Triggered Transitions** - Event-based transitions (`Transition.java:184-185`)
- **Transition Actions** - Actions executed during transitions
- **Entry/Exit/Do Actions** - State behaviors (`WithStateActions` in `FSM.java:89-96`)
- **Local Transitions** - Self-transitions (`Transition.java:181-182`)

### Events & Execution

- **Events** - With name, arguments, and consumed actions (`Event` interface in `FSM.java:200`)
- **Deferred Events** - Event queuing mechanism (`State.java:158-159`)
- **Run-to-Completion Semantics** - Event processing model
- **Synchronous & Asynchronous Executors** - Multiple execution modes

---

## Missing Features for Full UML 2.5 Compliance

### 1. Pseudostates (HIGH PRIORITY)

Pseudostates are transient vertices that enable advanced control flow. They are essential for complex state machine modeling.

#### History Pseudostates

- **Shallow History** - Remember last direct substate
- **Deep History** - Remember entire nested state configuration
- **Use Case:** Resuming interrupted state machine execution

#### Choice Pseudostate

- Dynamic conditional branching (guards evaluated at runtime)
- Diamond-shaped vertex with multiple outgoing transitions
- Different from junction (static branching)
- **Use Case:** Runtime decision-making based on computed values

#### Junction Pseudostate

- Static conditional branching (guards evaluated before entering)
- Chain multiple transitions together
- Small black circle representation
- **Use Case:** Simplify complex transition paths

#### Fork/Join Pseudostates

- **Fork** - Split single transition into concurrent regions (heavy bar with outgoing arrows)
- **Join** - Merge transitions from concurrent regions (heavy bar with incoming arrows)
- **Required for:** Orthogonal regions support

#### Entry/Exit Points

- **Entry Point** - Named entry into composite state or submachine
- **Exit Point** - Named exit from composite state or submachine
- **Use Case:** Enable encapsulation and reusability of composite states

#### Terminate Pseudostate

- Immediately terminate entire state machine execution
- Different from final state (which completes a region)

**Implementation Location:** Would extend the VMF model in `/subprojects/fsm/src/main/vmf/eu/mihosoft/vsm/model/vmfmodel/FSM.java`

---

### 2. Orthogonal Regions (HIGH PRIORITY)

Orthogonal regions enable concurrent state machine execution within a single composite state.

#### What's Missing

- **Regions** - Multiple concurrent state configurations within a single composite state
- **Parallel Execution** - Independent state machines running simultaneously
- **Synchronization** - Fork/join for entering/exiting regions

#### Current Limitation

`FSMState` can contain multiple child FSMs, but they don't represent true orthogonal regions with synchronized entry/exit.

#### Required Changes

- New `Region` interface/class to contain states
- Modify `FSMState` to contain regions instead of/in addition to child FSMs
- Implement fork/join pseudostates
- Synchronize region entry/exit
- Handle event broadcasting to all active regions

#### Example Use Case

```
CompositeState
├── Region1 (StateA, StateB)
└── Region2 (StateX, StateY)
```

Both regions active simultaneously, each with independent states. For example, a media player might have one region for playback control (playing/paused/stopped) and another region for volume control (muted/unmuted).

---

### 3. Submachine States (MEDIUM PRIORITY)

Submachine states enable reuse of state machine definitions.

#### What's Missing

- **Submachine State** - Reference to reusable state machine definition
- **Connection Points** - Entry/exit points for submachine encapsulation
- **State Machine References** - Ability to instantiate the same state machine template multiple times

#### Current Limitation

You can nest FSMs, but can't reference a shared state machine definition. Each nested FSM is a separate instance.

#### Required Changes

- `SubmachineState` interface extending State
- Reference to a template FSM
- Entry/exit point connections
- Parameter passing mechanism

#### Example Use Case

Define a "ConnectionHandling" state machine once and reuse it in multiple places (HTTP connection, database connection, etc.).

---

### 4. Internal Transitions (MEDIUM PRIORITY)

Internal transitions respond to events without exiting/entering states.

#### What's Missing

- Transitions that respond to events without exiting/entering states
- No exit action, no entry action, only transition effect executed

#### Current State

You have "local" transitions (self-transitions), but they still trigger exit/entry actions.

#### Required Changes

- Add `TransitionKind` enum: EXTERNAL, LOCAL, INTERNAL
- Modify transition execution logic in executor
- Update Transition interface to specify kind

#### Example Implementation

```java
interface Transition extends FSMChild {
    TransitionKind getKind(); // NEW
    // ... existing methods
}

enum TransitionKind {
    EXTERNAL,  // Always exit/enter (current default)
    LOCAL,     // Minimize exit/enter in composite states
    INTERNAL   // No exit/enter, just effect
}
```

#### Example Use Case

Increment a counter on each button press without resetting the state (no exit/entry actions).

---

### 5. Event Types (MEDIUM PRIORITY)

UML supports multiple event types beyond simple signal events.

#### Currently Supported

- Signal Events (named events with arguments)

#### Missing Event Types

**Time Events**
- Triggers after duration or at specific time
- Syntax: `after(duration)`, `at(time)`
- **Use Case:** Timeouts, periodic actions

**Change Events**
- Triggers when condition becomes true
- Syntax: `when(condition)`
- **Use Case:** React to state variable changes

**Call Events**
- Operation invocations (less common in embedded systems)

**Any Event**
- Wildcard to match any event
- **Use Case:** Default handlers, logging

#### Required Changes

- Event type hierarchy
- Timer infrastructure for time events
- Condition evaluation mechanism for change events
- Update executor to handle different event types

---

### 6. Protocol State Machines (LOW PRIORITY)

Protocol state machines define behavioral contracts rather than implementations.

#### What's Missing

- Protocol state machines define behavioral contracts
- Pre/post conditions on transitions
- Used for interfaces and protocols rather than implementation
- Cannot have entry/exit/do actions (only preconditions/postconditions)

#### When Needed

If you want to model communication protocols or API contracts separately from implementation.

#### Example Use Case

Define a TCP connection protocol (CLOSED → SYN_SENT → ESTABLISHED → ...) as a contract that implementations must follow.

---

### 7. Additional State Types (LOW PRIORITY)

#### Final State vs Terminate

- **Current:** Final states end regions
- **Missing:** Terminate pseudostate (ends entire state machine immediately, including all regions)

#### Simple vs Composite

- Could add explicit distinction in type system
- Currently implicit based on presence of child FSMs

---

## Implementation Roadmap

### Phase 1: Foundational Pseudostates (Weeks 1-2)

1. Create `Pseudostate` base interface
2. Implement Initial pseudostate (formalize existing)
3. Implement History pseudostates (shallow & deep)
4. Implement Choice pseudostate
5. Implement Junction pseudostate
6. Update executor to handle pseudostate semantics

**Deliverables:**
- Updated VMF model with pseudostate interfaces
- Executor logic for pseudostate transitions
- Unit tests for each pseudostate type

### Phase 2: Orthogonal Regions (Weeks 3-5)

1. Create `Region` model class
2. Modify `FSMState` to contain regions
3. Implement Fork pseudostate
4. Implement Join pseudostate
5. Update executor for concurrent execution
6. Implement region synchronization
7. Add event broadcasting to regions

**Deliverables:**
- Region model and containment structure
- Fork/join pseudostate implementation
- Concurrent execution support
- Comprehensive tests for parallel regions

### Phase 3: Enhanced Transitions (Week 6)

1. Add `TransitionKind` enum
2. Implement internal transitions
3. Add entry/exit point pseudostates
4. Update transition execution logic

**Deliverables:**
- TransitionKind enum and model updates
- Internal transition semantics
- Entry/exit point implementation
- Migration guide for existing code

### Phase 4: Submachines (Week 7)

1. Create `SubmachineState` interface
2. Implement state machine references
3. Add connection point mappings
4. Test reusability scenarios

**Deliverables:**
- Submachine state model
- Reference and instantiation mechanism
- Connection point mapping
- Examples demonstrating reuse

### Phase 5: Extended Events (Week 8)

1. Create event type hierarchy
2. Implement time events with timer
3. Implement change events with condition evaluator
4. Add event type handling to executor

**Deliverables:**
- Event type hierarchy
- Timer infrastructure
- Condition evaluator
- Updated executor with event type support

### Phase 6: Testing & Validation (Weeks 9-10)

1. Create comprehensive test suite
2. Validate against UML 2.5 semantics
3. Performance testing for complex machines
4. Documentation updates

**Deliverables:**
- Full test coverage for new features
- Performance benchmarks
- Updated Javadocs
- User guide with examples

---

## Key Files to Modify

### Model Definition

**Primary File:**
- `/subprojects/fsm/src/main/vmf/eu/mihosoft/vsm/model/vmfmodel/FSM.java`

**Changes:**
- Add `Pseudostate` interface and implementations (Initial, History, Choice, Junction, Fork, Join, EntryPoint, ExitPoint, Terminate)
- Add `Region` interface
- Add `SubmachineState` interface
- Add `TransitionKind` enum
- Update `FSMState` to contain regions
- Add event type hierarchy

### Executor Logic

**Files:**
- `/subprojects/executor/src/main/java/eu/mihosoft/vsm/executor/FSMExecutor.java`
- `/subprojects/fsm/src/main/java/eu/mihosoft/vsm/model/FSMExecutor.java`

**Changes:**
- Pseudostate handling logic
- Region concurrent execution
- Internal transition logic
- History state restoration
- Fork/join synchronization
- Event broadcasting to regions

### Test Coverage

**File:**
- `/subprojects/executor/src/test/java/eu/mihosoft/vsm/model/FSMTest.java`

**Changes:**
- Add tests for each new feature
- Add integration tests for complex scenarios
- Add performance tests

---

## Estimated Effort

| Priority | Features | Estimated Time |
|----------|----------|----------------|
| High | Pseudostates + Orthogonal Regions | 5-6 weeks |
| Medium | Internal Transitions + Event Types + Submachines | 3-4 weeks |
| Low | Protocol State Machines | 1-2 weeks |
| Testing & Validation | Comprehensive testing and documentation | 2 weeks |
| **Total** | **Full UML 2.5 Compliance** | **8-11 weeks** |

---

## Backward Compatibility Considerations

Most additions can be made backward-compatible:

### Non-Breaking Changes
- Existing FSMs continue to work without modification
- New features opt-in through new model elements
- Default behaviors preserved for existing code
- Existing transitions default to EXTERNAL kind

### Potentially Breaking Changes

#### Region Introduction
The biggest potential breaking change would be introducing `Region` as the container for states within composite states. This might require:
- Migration of existing `FSMState` usage
- API changes for state containment

**Mitigation Strategy:**
- Provide default region for backward compatibility
- Offer migration tools/scripts
- Maintain deprecated API for transition period

#### Model Generation
VMF code generation might produce different APIs with new model elements.

**Mitigation Strategy:**
- Version the model
- Provide clear migration documentation
- Use @Deprecated annotations for transitional period

---

## Compliance Validation

To validate UML compliance, the following should be verified:

### Behavioral Semantics
- [ ] Run-to-completion processing
- [ ] Event deferral and queue management
- [ ] Least Common Ancestor (LCA) calculation for transitions
- [ ] Proper entry/exit action execution order
- [ ] Guard evaluation semantics

### Structural Semantics
- [ ] State containment hierarchy
- [ ] Region orthogonality
- [ ] Submachine encapsulation
- [ ] Connection point mapping

### Advanced Features
- [ ] History state restoration (shallow and deep)
- [ ] Completion transitions (no trigger)
- [ ] Multiple transition selection (priority handling)
- [ ] Orthogonal region synchronization

---

## References

- [UML 2.5.1 Specification](https://www.omg.org/spec/UML/2.5.1/)
- [UML State Machine - Wikipedia](https://en.wikipedia.org/wiki/UML_state_machine)
- [State Machine Diagram Tutorial - Sparx Systems](https://sparxsystems.com/resources/tutorials/uml2/state-diagram.html)
- [UML State Machine Diagrams Overview](https://www.uml-diagrams.org/state-machine-diagrams.html)

---

## Conclusion

The VSM project has a solid foundation with core state machine features already implemented. Reaching full UML 2.5 compliance requires adding:

**Critical Features:**
1. Complete pseudostate support
2. Orthogonal regions with fork/join
3. Internal transitions

**Important Features:**
4. Submachine states for reusability
5. Extended event types

**Optional Features:**
6. Protocol state machines

The modular architecture and use of VMF for code generation positions the project well for these enhancements. Most features can be added incrementally with minimal breaking changes.

---

**Next Steps:**

1. Prioritize features based on user needs
2. Review and approve this roadmap
3. Create detailed design documents for Phase 1
4. Begin implementation starting with pseudostates
