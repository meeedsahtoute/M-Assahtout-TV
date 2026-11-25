pub use streamtube_core::i18n::*;
pub use streamtube_core::tf;

pub fn apply_component_locale() {
    gpui_kit::component::set_locale(language().code());
}
