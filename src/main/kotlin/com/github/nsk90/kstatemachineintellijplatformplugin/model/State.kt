package com.github.nsk90.kstatemachineintellijplatformplugin.model

open class State(
    val name: String,
    val states: List<State>,
    val transitions: List<Transition>,
)