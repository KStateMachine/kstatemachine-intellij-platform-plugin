package com.github.nsk90.kstatemachineintellijplatformplugin.model

class StateMachine(
    name: String,
    states: List<State>,
    transitions: List<Transition>,
): State(name, states, transitions)
