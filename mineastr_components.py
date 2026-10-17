"""组件归属和兼容字段 / Component ownership and compatibility fields.

领域服务按插件实例创建，兼容尚未执行 __init__ 的轻量测试实例。
Services belong to one plugin instance and support lightweight test construction.
No global component instances, background tasks or hidden attribute forwarding.
"""

from typing import TypeVar

T = TypeVar("T")


def component_for(plugin, service_type: type[T]) -> T:
    """按需创建纯内存服务；构造过程不能启动任务或执行 I/O。
    Lazily create an in-memory service; constructors must not start tasks or I/O.
    """
    components = plugin.__dict__.setdefault("_components", {})
    if service_type not in components:
        components[service_type] = service_type(plugin)
    return components[service_type]


class ServiceField:
    """旧字段名到唯一状态所有者的显式映射 / Explicit legacy-name to state-owner mapping."""

    def __init__(self, service_type, name: str):
        self.service_type = service_type
        self.name = name

    def __get__(self, plugin, owner=None):
        if plugin is None:
            return self
        return getattr(component_for(plugin, self.service_type), self.name)

    def __set__(self, plugin, value):
        setattr(component_for(plugin, self.service_type), self.name, value)
