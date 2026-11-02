// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI
import UIKit

/// Double tap and long press on the Você tab (ADR 0135). SwiftUI `Tab` has neither, so this finds the `UITabBar` of
/// the enclosing tab bar controller and adds a double tap and a long press recognizer to it. A long press (hold) on Você
/// opens the account switcher panel right on top of the tab bar (MZ, 2 Nov 2026). A tap is not cancelled, so a tap on
/// Você still opens Você; a recognized hold cancels the touch, so letting go does not open Você.
struct TabBarGestures: UIViewRepresentable {
    /// The accessibility label of the Você tab item: it finds the item in the tab bar.
    let voceLabel: String
    let onDoubleTap: () -> Void
    /// The switcher panel, built when the hold begins: the distance from its bottom edge to the bottom of the screen,
    /// and the call that closes it.
    let switcher: (_ bottomInset: CGFloat, _ close: @escaping () -> Void) -> AnyView
    /// Two or more accounts: the Você item gets the VoiceOver action that opens the switcher (spec app-tabs).
    var showsSwitchAction = false

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    func makeUIView(context: Context) -> ProbeView {
        let view = ProbeView()
        view.coordinator = context.coordinator
        return view
    }

    func updateUIView(_ view: ProbeView, context: Context) {
        context.coordinator.voceLabel = voceLabel
        context.coordinator.onDoubleTap = onDoubleTap
        context.coordinator.switcher = switcher
        context.coordinator.showsSwitchAction = showsSwitchAction
        view.install()
    }

    /// An empty view that looks for the tab bar once it is in a window.
    final class ProbeView: UIView {
        weak var coordinator: Coordinator?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            install()
        }

        func install() {
            guard let coordinator, let root = window?.rootViewController,
                  let tabBar = Self.tabBarController(in: root)?.tabBar else { return }
            coordinator.attach(to: tabBar)
        }

        private static func tabBarController(in controller: UIViewController) -> UITabBarController? {
            if let tabs = controller as? UITabBarController {
                return tabs
            }
            for child in controller.children + [controller.presentedViewController].compactMap({ $0 }) {
                if let tabs = tabBarController(in: child) {
                    return tabs
                }
            }
            return nil
        }
    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var voceLabel = ""
        var onDoubleTap: () -> Void = {}
        var switcher: (CGFloat, @escaping () -> Void) -> AnyView = { _, _ in AnyView(EmptyView()) }
        var showsSwitchAction = false
        private weak var tabBar: UITabBar?
        private lazy var doubleTap: UITapGestureRecognizer = {
            let recognizer = UITapGestureRecognizer(target: self, action: #selector(didDoubleTap(_:)))
            recognizer.numberOfTapsRequired = 2
            recognizer.cancelsTouchesInView = false
            recognizer.delaysTouchesEnded = false
            recognizer.delegate = self
            return recognizer
        }()
        private lazy var longPress: UILongPressGestureRecognizer = {
            let recognizer = UILongPressGestureRecognizer(target: self, action: #selector(didLongPress(_:)))
            recognizer.minimumPressDuration = 0.5
            recognizer.delaysTouchesEnded = false
            recognizer.delegate = self
            return recognizer
        }()

        func attach(to tabBar: UITabBar) {
            updateSwitchAction(in: tabBar)
            guard self.tabBar !== tabBar else { return }
            self.tabBar?.removeGestureRecognizer(doubleTap)
            self.tabBar?.removeGestureRecognizer(longPress)
            tabBar.addGestureRecognizer(doubleTap)
            tabBar.addGestureRecognizer(longPress)
            self.tabBar = tabBar
        }

        /// VoiceOver cannot hold, and Você has no account rows: the Você item carries a custom action that opens the
        /// same switcher panel (spec app-tabs). Set again on each update, since the tab bar can rebuild its items.
        private func updateSwitchAction(in tabBar: UITabBar) {
            guard let item = voceItem(in: tabBar) else { return }
            item.accessibilityCustomActions = showsSwitchAction
                ? [UIAccessibilityCustomAction(name: BipeStrings.a11ySwitchAccount) { [weak self] _ in
                    self?.presentSwitcher()
                    return true
                }]
                : nil
        }

        @objc private func didDoubleTap(_ recognizer: UITapGestureRecognizer) {
            guard recognizer.state == .ended, isOnVoce(recognizer) else { return }
            onDoubleTap()
        }

        @objc private func didLongPress(_ recognizer: UILongPressGestureRecognizer) {
            guard recognizer.state == .began, isOnVoce(recognizer) else { return }
            presentSwitcher()
        }

        /// The panel in a clear full-screen host, its bottom edge just above the tab bar.
        private func presentSwitcher() {
            guard let tabBar, let window = tabBar.window, var top = window.rootViewController else { return }
            while let presented = top.presentedViewController { top = presented }
            guard !(top is SwitcherHost) else { return }
            let barTop = tabBar.convert(tabBar.bounds, to: window).minY
            let host = SwitcherHost(rootView: AnyView(EmptyView()))
            host.rootView = switcher(window.bounds.height - barTop + 4) { [weak host] in host?.dismiss(animated: false) }
            host.modalPresentationStyle = .overFullScreen
            host.view.backgroundColor = .clear
            BipeHaptic.hold.play()
            top.present(host, animated: false)
        }

        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                               shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
            true
        }

        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            isOnVoce(gestureRecognizer)
        }

        private func isOnVoce(_ recognizer: UIGestureRecognizer) -> Bool {
            guard let tabBar, let item = voceItem(in: tabBar) else { return false }
            return item.convert(item.bounds, to: tabBar).contains(recognizer.location(in: tabBar))
        }

        /// The Você item: the view whose accessibility label is the tab's title. Else the item that holds the title
        /// label: the largest view above that label that is narrower than half the tab bar.
        private func voceItem(in tabBar: UITabBar) -> UIView? {
            var stack: [UIView] = [tabBar]
            var title: UIView?
            while !stack.isEmpty {
                let view = stack.removeFirst()
                if view !== tabBar, view.isAccessibilityElement,
                   let label = view.accessibilityLabel, label.hasPrefix(voceLabel) {
                    return view
                }
                if title == nil, let label = view as? UILabel, label.text == voceLabel {
                    title = label
                }
                stack.append(contentsOf: view.subviews)
            }
            guard var item = title else { return nil }
            while let parent = item.superview, parent !== tabBar, parent.bounds.width < tabBar.bounds.width / 2 {
                item = parent
            }
            return item
        }
    }
}

private final class SwitcherHost: UIHostingController<AnyView> {}
