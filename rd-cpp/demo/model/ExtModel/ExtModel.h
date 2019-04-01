#ifndef ExtModel_H
#define ExtModel_H

#include "Buffer.h"
#include "Identities.h"
#include "MessageBroker.h"
#include "Protocol.h"
#include "RdId.h"
#include "RdList.h"
#include "RdMap.h"
#include "RdProperty.h"
#include "RdSet.h"
#include "RdSignal.h"
#include "RName.h"
#include "ISerializable.h"
#include "Polymorphic.h"
#include "NullableSerializer.h"
#include "ArraySerializer.h"
#include "InternedSerializer.h"
#include "SerializationCtx.h"
#include "Serializers.h"
#include "ISerializersOwner.h"
#include "IUnknownInstance.h"
#include "RdExtBase.h"
#include "RdCall.h"
#include "RdEndpoint.h"
#include "RdTask.h"
#include "gen_util.h"

#include <iostream>
#include <cstring>
#include <cstdint>
#include <vector>
#include <type_traits>
#include <utility>

#include "optional.hpp"

#include "DemoModel.h"


#pragma warning( push )
#pragma warning( disable:4250 )
#pragma warning( disable:4307 )
namespace demo {
    class ExtModel : public rd::RdExtBase
    {
        
        //companion
        
        public:
        struct ExtModelSerializersOwner : public rd::ISerializersOwner {
            void registerSerializersCore(rd::Serializers const& serializers) const override;
        };
        
        static const ExtModelSerializersOwner serializersOwner;
        
        
        public:
        
        
        //extension
        static ExtModel const & getOrCreateExtensionOf(DemoModel & pointcut);
        
        //custom serializers
        private:
        
        //fields
        protected:
        rd::RdSignal<rd::Void, rd::Polymorphic<rd::Void>> checker_;
        
        
        //initializer
        private:
        void initialize();
        
        //primary ctor
        public:
        ExtModel(rd::RdSignal<rd::Void, rd::Polymorphic<rd::Void>> checker_);
        
        //secondary constructor
        
        //default ctors and dtors
        
        ExtModel();
        
        ExtModel(ExtModel &&) = delete;
        
        ExtModel& operator=(ExtModel &&) = delete;
        
        virtual ~ExtModel() = default;
        
        //reader
        
        //writer
        
        //virtual init
        void init(rd::Lifetime lifetime) const override;
        
        //identify
        void identify(const rd::Identities &identities, rd::RdId const &id) const override;
        
        //getters
        rd::RdSignal<rd::Void, rd::Polymorphic<rd::Void>> const & get_checker() const;
        
        //intern
        
        //equals trait
        private:
        
        //equality operators
        public:
        friend bool operator==(const ExtModel &lhs, const ExtModel &rhs);
        friend bool operator!=(const ExtModel &lhs, const ExtModel &rhs);
        
        //hash code trait
        
        //type name trait
    };
};

#pragma warning( pop )


//hash code trait

#endif // ExtModel_H