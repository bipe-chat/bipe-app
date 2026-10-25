// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI
import UIKit

/// Double tap and long press on the Você tab (ADR 0135). SwiftUI `Tab` has neither, so this finds the `UITabBar` of
/// the enclosing tab bar controller and adds a double tap recognizer and a context menu interaction to it. A long press
/// (hold) on Você opens the native account menu in place; neither cancels the tab bar's own taps, so a tap on Você
/// still opens Você. The tab bar of iOS 26 and later has its own press-and-drag gesture, which can hold back the menu
/// interaction's own press: a long press recognizer that runs at the same time then shows the same switcher as a popover
/// anchored on the Você item (MZ decision 25 Oct 2026).
struct TabBarGestures: UIViewRepresentable {
    /// The accessibility label of the Você tab item: it finds the item in the tab bar.
    let voceLabel: String
    let onDoubleTap: () -> Void
    /// The account menu, built when the long press begins so it shows the latest accounts.
    let menu: () -> UIMenu
    /// The same switcher as a view, for the popover when the menu does not show.
    let switcher: () -> AnyView
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
        context.coordinator.menu = menu
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

    final class Coordinator: NSObject, UIGestureRecognizerDelegate, UIContextMenuInteractionDelegate,
                             UIPopoverPresentationControllerDelegate {
        var voceLabel = ""
        var onDoubleTap: () -> Void = {}
        var menu: () -> UIMenu = { UIMenu(children: []) }
        var switcher: () -> AnyView = { AnyView(EmptyView()) }
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
            recognizer.cancelsTouchesInView = false
            recognizer.delaysTouchesEnded = false
            recognizer.delegate = self
            return recognizer
        }()
        private lazy var menuInteraction = UIContextMenuInteraction(delegate: self)
        private var menuShows = false

        func attach(to tabBar: UITabBar) {
            updateSwitchAction(in: tabBar)
            guard self.tabBar !== tabBar else { return }
            self.tabBar?.removeGestureRecognizer(doubleTap)
            self.tabBar?.removeGestureRecognizer(longPress)
            self.tabBar?.removeInteraction(menuInteraction)
            tabBar.addGestureRecognizer(doubleTap)
            tabBar.addGestureRecognizer(longPress)
            tabBar.addInteraction(menuInteraction)
            self.tabBar = tabBar
        }

        /// VoiceOver cannot hold, and Você has no account rows: the Você item carries a custom action that opens the
        /// same switcher popover (spec app-tabs). Set again on each update, since the tab bar can rebuild its items.
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

        /// Shows the switcher popover when the interaction's own press did not open the menu (it shows by then).
        @objc private func didLongPress(_ recognizer: UILongPressGestureRecognizer) {
            guard recognizer.state == .began, isOnVoce(recognizer) else { return }
            DispatchQueue.main.async { [weak self] in
                guard let self, !self.menuShows else { return }
                self.presentSwitcher()
            }
        }

        private func presentSwitcher() {
            guard let tabBar, let item = voceItem(in: tabBar), var top = tabBar.window?.rootViewController else { return }
            while let presented = top.presentedViewController { top = presented }
            let host = UIHostingController(rootView: switcher())
            host.modalPresentationStyle = .popover
            host.preferredContentSize = CGSize(width: 320, height: 360)
            if let popover = host.popoverPresentationController {
                popover.sourceView = tabBar
                popover.sourceRect = item.convert(item.bounds, to: tabBar)
                popover.permittedArrowDirections = .down
                popover.delegate = self
            }
            BipeHaptic.hold.play()
            top.present(host, animated: true)
        }

        /// A popover on iPhone too: the switcher opens in place, on the tab.
        func adaptivePresentationStyle(for controller: UIPresentationController,
                                       traitCollection: UITraitCollection) -> UIModalPresentationStyle {
            .none
        }

        func contextMenuInteraction(_ interaction: UIContextMenuInteraction,
                                    willDisplayMenuFor configuration: UIContextMenuConfiguration,
                                    animator: (any UIContextMenuInteractionAnimating)?) {
            menuShows = true
        }

        func contextMenuInteraction(_ interaction: UIContextMenuInteraction,
                                    willEndFor configuration: UIContextMenuConfiguration,
                                    animator: (any UIContextMenuInteractionAnimating)?) {
            menuShows = false
        }

        func contextMenuInteraction(_ interaction: UIContextMenuInteraction,
                                    configurationForMenuAtLocation location: CGPoint) -> UIContextMenuConfiguration? {
            guard let tabBar, let item = voceItem(in: tabBar),
                  item.convert(item.bounds, to: tabBar).contains(location) else { return nil }
            return UIContextMenuConfiguration(identifier: nil, previewProvider: nil) { [weak self] _ in self?.menu() }
        }

        /// The menu lifts the Você item, not the whole tab bar.
        func contextMenuInteraction(_ interaction: UIContextMenuInteraction,
                                    configuration: UIContextMenuConfiguration,
                                    highlightPreviewForItemWithIdentifier identifier: any NSCopying) -> UITargetedPreview? {
            guard let tabBar, let item = voceItem(in: tabBar), item.window != nil else { return nil }
            let parameters = UIPreviewParameters()
            parameters.backgroundColor = .clear
            return UITargetedPreview(view: item, parameters: parameters)
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
