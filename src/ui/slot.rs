//! The main video player lives outside the page tree so that video frames only
//! re-render the player itself, never the (cached) page around it. Pages place a
//! `player_slot()` where the video should appear; `SlotFollower` draws the player
//! over that slot in the same frame (it reads the slot bounds during prepaint).
use gpui_kit::*;
use std::cell::Cell;
use std::rc::Rc;

#[derive(Clone, Default)]
pub struct PlayerSlot(pub Rc<Cell<Option<Bounds<Pixels>>>>);

impl Global for PlayerSlot {}

/// Clears the recorded player slot bounds (call on navigation so the old page's
/// slot size is never briefly drawn on the new page).
pub fn clear_slot(cx: &App) {
    if let Some(s) = cx.try_global::<PlayerSlot>() {
        s.0.set(None);
    }
}

/// Placeholder element that records where the player should be drawn.
pub fn player_slot(cx: &App) -> impl IntoElement {
    let slot = cx.global::<PlayerSlot>().0.clone();
    canvas(move |b, _, _| slot.set(Some(b)), |_, _, _, _| {}).size_full()
}

pub struct SlotFollower {
    slot: Rc<Cell<Option<Bounds<Pixels>>>>,
    child: Option<AnyElement>,
    laid_size: Option<Size<Pixels>>,
    can_paint: bool,
}

impl SlotFollower {
    pub fn new(cx: &App, content: impl IntoElement) -> Self {
        Self::with_slot(cx.global::<PlayerSlot>().0.clone(), content)
    }

    /// Follows any slot (e.g. the thumbnail being previewed).
    pub fn with_slot(slot: Rc<Cell<Option<Bounds<Pixels>>>>, content: impl IntoElement) -> Self {
        let laid_size = slot.get().map(|b| b.size);
        let child = laid_size.map(|s| div().w(s.width).h(s.height).child(content).into_any_element());
        Self { slot, child, laid_size, can_paint: false }
    }
}

impl IntoElement for SlotFollower {
    type Element = Self;
    fn into_element(self) -> Self {
        self
    }
}

impl Element for SlotFollower {
    type RequestLayoutState = ();
    type PrepaintState = ();

    fn id(&self) -> Option<ElementId> {
        None
    }

    fn source_location(&self) -> Option<&'static core::panic::Location<'static>> {
        None
    }

    fn request_layout(
        &mut self,
        _id: Option<&GlobalElementId>,
        _inspector_id: Option<&InspectorElementId>,
        window: &mut Window,
        cx: &mut App,
    ) -> (LayoutId, ()) {
        let mut style = Style::default();
        style.position = Position::Absolute;
        style.inset.top = px(0.).into();
        style.inset.left = px(0.).into();
        let children: Vec<LayoutId> = self.child.as_mut().map(|c| c.request_layout(window, cx)).into_iter().collect();
        (window.request_layout(style, children, cx), ())
    }

    fn prepaint(
        &mut self,
        _id: Option<&GlobalElementId>,
        _inspector_id: Option<&InspectorElementId>,
        bounds: Bounds<Pixels>,
        _: &mut (),
        window: &mut Window,
        cx: &mut App,
    ) {
        self.can_paint = false;
        let Some(slot) = self.slot.get() else {
            window.request_animation_frame();
            return;
        };
        if Some(slot.size) != self.laid_size {
            // slot changed size since layout: draw once more with the new size
            window.request_animation_frame();
            // skip painting if orientation flipped (e.g. 16:9 Watch -> 9:16 Shorts)
            if let Some(ls) = self.laid_size {
                let old_land = f32::from(ls.width) > f32::from(ls.height);
                let new_land = f32::from(slot.size.width) > f32::from(slot.size.height);
                if old_land != new_land {
                    return;
                }
            } else {
                return;
            }
        }
        if let Some(child) = self.child.as_mut() {
            let offset = slot.origin - bounds.origin;
            window.with_element_offset(offset, |window| child.prepaint(window, cx));
            self.can_paint = true;
        }
    }

    fn paint(
        &mut self,
        _id: Option<&GlobalElementId>,
        _inspector_id: Option<&InspectorElementId>,
        _bounds: Bounds<Pixels>,
        _: &mut (),
        _: &mut (),
        window: &mut Window,
        cx: &mut App,
    ) {
        if self.can_paint {
            if let Some(child) = self.child.as_mut() {
                child.paint(window, cx);
            }
        }
    }
}
