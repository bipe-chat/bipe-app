// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import SwiftUI
import UIKit

/// Double tap and long press on the Você tab (ADR 0135). SwiftUI `Tab` has neither, so this finds the `UITabBar` of
/// the enclosing tab bar controller and adds two gesture recognizers to it. They never cancel the tab bar's own taps:
/// a tap on Você still opens Você.
struct TabBarGestures: UIViewRepresentable {
    /// The accessibility label of the Você tab item: it finds the item in the tab bar.
    let voceLabel: String
    let onDoubleTap: () -> Void
    let onLongPress: () -> Void

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
        context.coordinator.onLongPress = onLongPress
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
        var onLongPress: () -> Void = {}
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
            recognizer.cancelsTouchesInView = false
            recognizer.delegate = self
            return recognizer
        }()

        func attach(to tabBar: UITabBar) {
            guard self.tabBar !== tabBar else { return }
            self.tabBar?.removeGestureRecognizer(doubleTap)
            self.tabBar?.removeGestureRecognizer(longPress)
            tabBar.addGestureRecognizer(doubleTap)
            tabBar.addGestureRecognizer(longPress)
            self.tabBar = tabBar
        }

        @objc private func didDoubleTap(_ recognizer: UITapGestureRecognizer) {
            guard recognizer.state == .ended, isOnVoce(recognizer) else { return }
            onDoubleTap()
        }

        @objc private func didLongPress(_ recognizer: UILongPressGestureRecognizer) {
            guard recognizer.state == .began, isOnVoce(recognizer) else { return }
            onLongPress()
        }

        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                               shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
            true
        }

        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            isOnVoce(gestureRecognizer)
        }

        private func isOnVoce(_ recognizer: UIGestureRecognizer) -> Bool {
            guard let tabBar, let frame = voceFrame(in: tabBar) else { return false }
            return frame.contains(recognizer.location(in: tabBar))
        }

        /// The frame of the Você item: the view whose accessibility label is the tab's title.
        private func voceFrame(in tabBar: UITabBar) -> CGRect? {
            var stack: [UIView] = [tabBar]
            while !stack.isEmpty {
                let view = stack.removeFirst()
                if view !== tabBar, view.isAccessibilityElement,
                   let label = view.accessibilityLabel, label.hasPrefix(voceLabel) {
                    return view.convert(view.bounds, to: tabBar)
                }
                stack.append(contentsOf: view.subviews)
            }
            return nil
        }
    }
}
