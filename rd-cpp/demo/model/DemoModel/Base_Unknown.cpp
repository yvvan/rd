#include "Base_Unknown.h"


namespace demo {
    
    //companion
    
    //initializer
    void Base_Unknown::initialize()
    {
    }
    
    //primary ctor
    Base_Unknown::Base_Unknown(rd::RdId unknownId_, rd::Buffer::ByteArray unknownBytes_) :
    Base(), rd::IUnknownInstance(std::move(unknownId_))
    
    {
        initialize();
    }
    
    //secondary constructor
    
    //default ctors and dtors
    Base_Unknown::Base_Unknown()
    {
        initialize();
    }
    
    //reader
    Base_Unknown Base_Unknown::read(rd::SerializationCtx const& ctx, rd::Buffer const & buffer)
    {
        throw std::logic_error("Unknown instances should not be read via serializer");
    }
    
    //writer
    void Base_Unknown::write(rd::SerializationCtx const& ctx, rd::Buffer const& buffer) const
    {
        buffer.writeByteArrayRaw(unknownBytes_);
    }
    
    //virtual init
    
    //identify
    
    //getters
    
    //intern
    
    //equals trait
    bool Base_Unknown::equals(rd::ISerializable const& object) const
    {
        auto const &other = dynamic_cast<Base_Unknown const&>(object);
        if (this == &other) return true;
        
        return true;
    }
    
    //equality operators
    bool operator==(const Base_Unknown &lhs, const Base_Unknown &rhs){
        if (lhs.type_name() != rhs.type_name()) return false;
        return lhs.equals(rhs);
    }
    bool operator!=(const Base_Unknown &lhs, const Base_Unknown &rhs){
        return !(lhs == rhs);
    }
    
    //hash code trait
    size_t Base_Unknown::hashCode() const
    {
        size_t __r = 0;
        return __r;
    }
    std::string Base_Unknown::type_name() const
    {
        return "Base_Unknown";
    }
};